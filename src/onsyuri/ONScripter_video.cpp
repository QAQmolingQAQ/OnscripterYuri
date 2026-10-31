/*
 * ONScripter_video.cpp - internal video/audio decoding with FFmpeg.
 *
 * Replaces the external player on Android: movie and avi files are decoded
 * in process, rendered through the SDL renderer and their audio track is
 * mixed by SDL_mixer. The file is read through fopen/fopen_ons, so the smb
 * virtual path hook keeps working without copying the movie beforehand.
 *
 * v0.7.8, for OnscripterYuri android
 */

#if defined(ANDROID) && defined(USE_FFMPEG)

#include "ONScripter.h"
#include "Utils.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include <math.h>
#include <memory.h>
#include <mutex>
#include <new>
#include <vector>

#include <android/log.h>
#define VLOG(...) __android_log_print(ANDROID_LOG_INFO, "## onsyuri", __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- custom io

struct FileIO {
    FILE *fp;
};

int fileio_read(void *opaque, uint8_t *buf, int size);
int64_t fileio_seek(void *opaque, int64_t offset, int whence);

struct MovieInput {
    FileIO io;
    AVIOContext *avio;
    AVFormatContext *fmt;
};

bool movieInputOpen(const char *filename, MovieInput &mi)
{
    mi.avio = NULL;
    mi.fmt = NULL;
    mi.io.fp = fopen(filename, "rb");
    if (!mi.io.fp) {
        VLOG("ffmpeg: open video failed %s", filename);
        return false;
    }

    mi.avio = avio_alloc_context(
            (unsigned char *)av_malloc(65536), 65536, 0, &mi.io,
            fileio_read, NULL, fileio_seek);
    mi.fmt = avformat_alloc_context();
    if (!mi.avio || !mi.fmt) {
        if (mi.fmt) avformat_free_context(mi.fmt);
        if (mi.avio) { av_free(mi.avio->buffer); avio_context_free(&mi.avio); }
        if (mi.io.fp) fclose(mi.io.fp);
        mi.fmt = NULL;
        mi.avio = NULL;
        mi.io.fp = NULL;
        return false;
    }
    mi.fmt->pb = mi.avio;
    mi.fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    if (avformat_open_input(&mi.fmt, "", NULL, NULL) < 0 ||
        avformat_find_stream_info(mi.fmt, NULL) < 0) {
        VLOG("ffmpeg: unsupported video %s", filename);
        if (mi.fmt) avformat_close_input(&mi.fmt);
        if (mi.avio) {
            av_freep(&mi.avio->buffer);
            avio_context_free(&mi.avio);
        }
        if (mi.io.fp) { fclose(mi.io.fp); mi.io.fp = NULL; }
        return false;
    }
    return true;
}

void movieInputClose(MovieInput &mi)
{
    if (mi.fmt) avformat_close_input(&mi.fmt);
    if (mi.avio) {
        av_freep(&mi.avio->buffer);
        avio_context_free(&mi.avio);
    }
    if (mi.io.fp) {
        fclose(mi.io.fp);
        mi.io.fp = NULL;
    }
}

struct MemIO {
    const unsigned char *data;
    long size;
    long pos;
};

int fileio_read(void *opaque, uint8_t *buf, int size)
{
    FileIO *io = (FileIO *)opaque;
    int n = (int)fread(buf, 1, size, io->fp);
    if (n <= 0) return AVERROR_EOF;
    return n;
}

int64_t fileio_seek(void *opaque, int64_t offset, int whence)
{
    FILE *fp = ((FileIO *)opaque)->fp;
    if (whence & AVSEEK_SIZE) {
        long cur = ftell(fp);
        if (fseek(fp, 0, SEEK_END) != 0) return AVERROR(EIO);
        long size = ftell(fp);
        fseek(fp, cur, SEEK_SET);
        return size;
    }
    whence &= ~AVSEEK_FORCE;
    if (fseek(fp, offset, whence) != 0) return AVERROR(EIO);
    return ftell(fp);
}

int memio_read(void *opaque, uint8_t *buf, int size)
{
    MemIO *io = (MemIO *)opaque;
    long left = io->size - io->pos;
    if (left <= 0) return AVERROR_EOF;
    if (size > left) size = (int)left;
    memcpy(buf, io->data + io->pos, size);
    io->pos += size;
    return size;
}

int64_t memio_seek(void *opaque, int64_t offset, int whence)
{
    MemIO *io = (MemIO *)opaque;
    if (whence & AVSEEK_SIZE) return io->size;
    whence &= ~AVSEEK_FORCE;
    long pos;
    switch (whence) {
        case SEEK_SET: pos = (long)offset; break;
        case SEEK_CUR: pos = io->pos + (long)offset; break;
        case SEEK_END: pos = io->size + (long)offset; break;
        default: return AVERROR(EIO);
    }
    if (pos < 0 || pos > io->size) return AVERROR(EIO);
    io->pos = pos;
    return pos;
}

// --------------------------------------------------------------- audio fifo

struct VideoAudio {
    std::vector<unsigned char> data;
    size_t pos;
    int gain; // 0..100
    std::mutex mutex;
};

void videoAudioCallback(void *udata, Uint8 *stream, int len)
{
    VideoAudio *audio = (VideoAudio *)udata;
    std::lock_guard<std::mutex> lock(audio->mutex);

    size_t left = audio->data.size() > audio->pos ? audio->data.size() - audio->pos : 0;
    int copy = (int)(left < (size_t)len ? left : (size_t)len);
    if (copy > 0) {
        memcpy(stream, audio->data.data() + audio->pos, copy);
        audio->pos += copy;
    }
    if (copy < len) memset(stream + copy, 0, len - copy);

    if (audio->gain != 100) {
        short *samples = (short *)(void *)stream;
        int count = len / 2;
        for (int i = 0; i < count; i++) {
            samples[i] = (short)(samples[i] * audio->gain / 100);
        }
    }
}

// -------------------------------------------------------------------- wav

int writeWav(const std::vector<unsigned char> &pcm, int rate, int channels,
             unsigned char **out, long *outlen)
{
    long data_len = (long)pcm.size();
    long total = 44 + data_len;
    unsigned char *buf = new (std::nothrow) unsigned char[total];
    if (!buf) return -1;

    unsigned short format = 1;
    unsigned short ch = (unsigned short)channels;
    unsigned int r = (unsigned int)rate;
    unsigned short bits = 16;
    unsigned int byte_rate = r * ch * bits / 8;
    unsigned short block_align = (unsigned short)(ch * bits / 8);
    unsigned int data_size = (unsigned int)data_len;
    unsigned int riff_size = (unsigned int)(total - 8);
    unsigned int fmt_size = 16;

    memcpy(buf + 0, "RIFF", 4);
    memcpy(buf + 4, &riff_size, 4);
    memcpy(buf + 8, "WAVE", 4);
    memcpy(buf + 12, "fmt ", 4);
    memcpy(buf + 16, &fmt_size, 4);
    memcpy(buf + 20, &format, 2);
    memcpy(buf + 22, &ch, 2);
    memcpy(buf + 24, &r, 4);
    memcpy(buf + 28, &byte_rate, 4);
    memcpy(buf + 32, &block_align, 2);
    memcpy(buf + 34, &bits, 2);
    memcpy(buf + 36, "data", 4);
    memcpy(buf + 40, &data_size, 4);
    if (data_len > 0) memcpy(buf + 44, pcm.data(), data_len);

    *out = buf;
    *outlen = total;
    return 0;
}

// Resample one decoded frame into the mixer format.
void appendAudioFrame(SwrContext *swr, AVFrame *frame, int outChannels,
                      std::vector<unsigned char> &pcm)
{
    int out_samples = swr_get_out_samples(swr, frame->nb_samples);
    if (out_samples <= 0) return;

    int bytes = out_samples * outChannels * 2;
    unsigned char *tmp = (unsigned char *)av_malloc(bytes);
    if (!tmp) return;

    uint8_t *out_ptr = tmp;
    int got = swr_convert(swr, &out_ptr, out_samples,
                          (const uint8_t **)frame->data, frame->nb_samples);
    if (got > 0) {
        pcm.insert(pcm.end(), tmp, tmp + got * outChannels * 2);
    }
    av_free(tmp);
}

// Decode the whole audio stream to S16 interleaved pcm.
void decodeAudioStream(AVFormatContext *fmt, int stream_index,
                       int out_rate, int out_channels,
                       std::vector<unsigned char> &pcm, int64_t *duration_ms)
{
    AVStream *stream = fmt->streams[stream_index];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) return;

    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return;
    if (avcodec_parameters_to_context(ctx, stream->codecpar) < 0 ||
        avcodec_open2(ctx, codec, NULL) < 0) {
        avcodec_free_context(&ctx);
        return;
    }

    SwrContext *swr = NULL;
    AVChannelLayout out_layout;
    if (out_channels == 1) av_channel_layout_default(&out_layout, 1);
    else av_channel_layout_default(&out_layout, 2);

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!pkt || !frame) {
        av_packet_free(&pkt);
        av_frame_free(&frame);
        avcodec_free_context(&ctx);
        return;
    }

    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == stream_index) {
            if (avcodec_send_packet(ctx, pkt) >= 0) {
                while (avcodec_receive_frame(ctx, frame) == 0) {
                    if (!swr) {
                        if (swr_alloc_set_opts2(&swr, &out_layout, AV_SAMPLE_FMT_S16,
                                out_rate, &frame->ch_layout, (AVSampleFormat)frame->format,
                                frame->sample_rate, 0, NULL) < 0 ||
                            swr_init(swr) < 0) {
                            av_packet_unref(pkt);
                            goto finish;
                        }
                    }
                    appendAudioFrame(swr, frame, out_channels, pcm);
                    av_frame_unref(frame);
                }
            }
        }
        av_packet_unref(pkt);
    }

    // flush the decoder
    avcodec_send_packet(ctx, NULL);
    while (avcodec_receive_frame(ctx, frame) == 0) {
        if (swr) appendAudioFrame(swr, frame, out_channels, pcm);
        av_frame_unref(frame);
    }
    if (swr) {
        for (;;) {
            int out_samples = swr_get_out_samples(swr, 0);
            if (out_samples <= 0) break;
            unsigned char *tmp = (unsigned char *)av_malloc(out_samples * out_channels * 2);
            if (!tmp) break;
            uint8_t *out_ptr = tmp;
            int got = swr_convert(swr, &out_ptr, out_samples, NULL, 0);
            if (got > 0) pcm.insert(pcm.end(), tmp, tmp + got * out_channels * 2);
            av_free(tmp);
            if (got <= 0) break;
        }
    }

finish:
    if (duration_ms) *duration_ms = -1;
    swr_free(&swr);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    avcodec_free_context(&ctx);
}

} // namespace

// -------------------------------------------------------------------------
// public helpers

/**
 * Decode an arbitrary audio file (given as memory) to a wav image so that
 * SDL_mixer can play formats it cannot decode by itself (wma/aac/flac/...).
 * Returns 0 on success, the caller owns the buffer.
 */
int ffmpegDecodeAudioToWav(const unsigned char *data, long length,
                           int out_rate, int out_channels,
                           unsigned char **out, long *outlen)
{
    if (!data || length <= 0 || !out || !outlen) return -1;

    MemIO io = { data, length, 0 };
    AVIOContext *avio = avio_alloc_context(
            (unsigned char *)av_malloc(32768), 32768, 0, &io,
            memio_read, NULL, memio_seek);
    if (!avio) return -1;

    AVFormatContext *fmt = avformat_alloc_context();
    if (!fmt) {
        av_free(avio->buffer);
        avio_context_free(&avio);
        return -1;
    }
    fmt->pb = avio;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    int ret = -1;
    int stream_index = -1;
    std::vector<unsigned char> pcm;

    if (avformat_open_input(&fmt, "", NULL, NULL) < 0) goto finish_open;
    if (avformat_find_stream_info(fmt, NULL) < 0) goto finish_open;

    stream_index = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream_index < 0) goto finish_open;

    decodeAudioStream(fmt, stream_index, out_rate, out_channels, pcm, NULL);
    if (!pcm.empty() && writeWav(pcm, out_rate, out_channels, out, outlen) == 0) ret = 0;

finish_open:
    avformat_close_input(&fmt);
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    return ret;
}

// -------------------------------------------------------------------------
// internal video player

int ONScripter::playVideoFFmpeg(const char *filename, bool click_flag, bool loop_flag)
{
    if (!renderer) return 0;

    VLOG("ffmpeg: play %s", filename);

    // pass 1: decode the audio track, the video pass below starts from byte 0
    VideoAudio video_audio;
    video_audio.pos = 0;
    video_audio.gain = music_volume;
    int out_channels = audio_format.channels > 0 ? audio_format.channels : 2;
    if (out_channels > 2) out_channels = 2;
    int out_rate = audio_format.freq > 0 ? audio_format.freq : 44100;

    if (audio_open_flag) {
        MovieInput audio_in;
        if (movieInputOpen(filename, audio_in)) {
            int ai = av_find_best_stream(audio_in.fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
            if (ai >= 0) {
                decodeAudioStream(audio_in.fmt, ai, out_rate, out_channels,
                                  video_audio.data, NULL);
            }
            movieInputClose(audio_in);
        }
    }
    bool has_audio = !video_audio.data.empty();
    VLOG("ffmpeg: audio %.1f seconds", (double)video_audio.data.size() / (out_rate * out_channels * 2));

    // pass 2: video playback
    MovieInput video_in;
    if (!movieInputOpen(filename, video_in)) return 0;
    AVFormatContext *fmt = video_in.fmt;

    int video_index = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (video_index < 0) {
        VLOG("ffmpeg: no video stream in %s", filename);
        movieInputClose(video_in);
        return 0;
    }

    AVStream *vstream = fmt->streams[video_index];
    const AVCodec *vcodec = avcodec_find_decoder(vstream->codecpar->codec_id);
    AVCodecContext *vctx = avcodec_alloc_context3(vcodec);
    if (!vcodec || !vctx ||
        avcodec_parameters_to_context(vctx, vstream->codecpar) < 0 ||
        avcodec_open2(vctx, vcodec, NULL) < 0) {
        VLOG("ffmpeg: can not decode video stream");
        if (vctx) avcodec_free_context(&vctx);
        movieInputClose(video_in);
        return 0;
    }

    // -------------------------------------------------------- video output

    int video_w = vctx->width;
    int video_h = vctx->height;
    if (video_w <= 0 || video_h <= 0) {
        video_w = vstream->codecpar->width;
        video_h = vstream->codecpar->height;
    }

    SwsContext *sws = sws_getContext(video_w, video_h, vctx->pix_fmt,
                                     video_w, video_h, AV_PIX_FMT_RGBA,
                                     SWS_BILINEAR, NULL, NULL, NULL);
    SDL_Texture *vtex = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGBA32,
                                          SDL_TEXTUREACCESS_STREAMING,
                                          video_w, video_h);
    unsigned char *rgb = (unsigned char *)av_malloc(video_w * video_h * 4);
    if (!sws || !vtex || !rgb) {
        utils::printError("ffmpeg: can not create the video output\n");
        if (sws) sws_freeContext(sws);
        if (vtex) SDL_DestroyTexture(vtex);
        av_free(rgb);
        avcodec_free_context(&vctx);
        movieInputClose(video_in);
        return 0;
    }

    if (has_audio) Mix_HookMusic(videoAudioCallback, &video_audio);

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    int64_t pts_base = AV_NOPTS_VALUE;
    double fallback_pts = 0.0;
    double frame_duration = 0.0;
    if (vstream->avg_frame_rate.num > 0 && vstream->avg_frame_rate.den > 0) {
        frame_duration = 1000.0 * vstream->avg_frame_rate.den / vstream->avg_frame_rate.num;
    }

    Uint32 clock_start = SDL_GetTicks();
    bool quit = false;
    bool done = false;
    bool last_frame_ok = false;
    int rendered = 0;

    while (!done && !quit) {
        int read_ret = av_read_frame(fmt, pkt);
        if (read_ret < 0) {
            avcodec_send_packet(vctx, NULL);
        } else if (pkt->stream_index != video_index) {
            av_packet_unref(pkt);
            continue;
        } else {
            avcodec_send_packet(vctx, pkt);
            av_packet_unref(pkt);
        }

        while (!done && !quit && avcodec_receive_frame(vctx, frame) == 0) {
            double this_pts;
            if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                if (pts_base == AV_NOPTS_VALUE) pts_base = frame->best_effort_timestamp;
                this_pts = (double)(frame->best_effort_timestamp - pts_base) *
                           av_q2d(vstream->time_base) * 1000.0;
            } else {
                this_pts = fallback_pts;
                fallback_pts += frame_duration > 0 ? frame_duration : 40.0;
            }

            // wait until the frame time, polling events while waiting
            Uint32 target = clock_start + (Uint32)(this_pts > 0 ? this_pts : 0);
            while (!quit && !done) {
                Uint32 now = SDL_GetTicks();
                if (now >= target) break;
                Uint32 wait = target - now;
                if (wait > 10) wait = 10;
                SDL_Delay(wait);

                SDL_Event event;
                while (SDL_PollEvent(&event)) {
                    if (event.type == SDL_QUIT) {
                        quit = true;
                    } else if (click_flag && event.type == SDL_KEYUP) {
                        SDL_Keycode key = event.key.keysym.sym;
                        if (key == SDLK_RETURN || key == SDLK_SPACE || key == SDLK_ESCAPE)
                            done = true;
                    } else if (click_flag && event.type == SDL_MOUSEBUTTONUP) {
                        done = true;
                    }
                }
            }
            if (done || quit) {
                av_frame_unref(frame);
                break;
            }

            // convert and render
            const uint8_t *src[4] = { frame->data[0], frame->data[1],
                                      frame->data[2], frame->data[3] };
            int src_linesize[4] = { frame->linesize[0], frame->linesize[1],
                                    frame->linesize[2], frame->linesize[3] };
            int dst_linesize[4] = { video_w * 4, 0, 0, 0 };
            sws_scale(sws, src, src_linesize, 0, video_h, (uint8_t *const *)&rgb,
                      dst_linesize);

            int out_w = 0, out_h = 0;
            SDL_GetRendererOutputSize(renderer, &out_w, &out_h);
            if (out_w <= 0 || out_h <= 0) SDL_GetWindowSize(window, &out_w, &out_h);

            SDL_Rect dst;
            float scale_w = out_w > 0 ? (float)out_w / video_w : 1.0f;
            float scale_h = out_h > 0 ? (float)out_h / video_h : 1.0f;
            float scale = scale_w < scale_h ? scale_w : scale_h;
            dst.w = (int)(video_w * scale);
            dst.h = (int)(video_h * scale);
            dst.x = (out_w - dst.w) / 2;
            dst.y = (out_h - dst.h) / 2;

            SDL_UpdateTexture(vtex, NULL, rgb, video_w * 4);
            SDL_RenderClear(renderer);
            SDL_RenderCopy(renderer, vtex, NULL, &dst);
            SDL_RenderPresent(renderer);
            last_frame_ok = true;
            if (++rendered == 1) VLOG("ffmpeg: first frame %dx%d pts %.0fms", video_w, video_h, this_pts);
            av_frame_unref(frame);
        }

        if (read_ret < 0) {
            if (loop_flag && !done && !quit) {
                av_seek_frame(fmt, video_index, 0, AVSEEK_FLAG_BACKWARD);
                avcodec_flush_buffers(vctx);
                pts_base = AV_NOPTS_VALUE;
                fallback_pts = 0.0;
                clock_start = SDL_GetTicks();
                if (has_audio) {
                    std::lock_guard<std::mutex> lock(video_audio.mutex);
                    video_audio.pos = 0;
                }
                continue;
            }
            break;
        }
    }

    VLOG("ffmpeg: video done, %d frames", rendered);
    if (has_audio) Mix_HookMusic(NULL, NULL);

    av_frame_free(&frame);
    av_packet_free(&pkt);
    SDL_DestroyTexture(vtex);
    av_free(rgb);
    sws_freeContext(sws);
    avcodec_free_context(&vctx);
    movieInputClose(video_in);

    if (quit) return 1;
    if (last_frame_ok) {
        screen_dirty_flag = true;
        flushDirect(screen_rect, refreshMode());
    }
    return 0;
}

#endif // ANDROID && USE_FFMPEG
