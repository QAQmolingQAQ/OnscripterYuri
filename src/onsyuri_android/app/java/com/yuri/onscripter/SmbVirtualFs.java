/**
 * Virtual file system for SMB games.
 *
 * A game can run straight over the network: files are downloaded on demand
 * into a per-game cache directory, so the whole game does not have to be
 * copied before starting. Files written by the game stay in the same local
 * overlay and are re-used by later reads.
 *
 * The virtual root looks like "/onsyuri_smb/<token>", which is what the
 * emscripten native side passes to ONScripter.getFD().
 *
 * v0.7.8, for OnscripterYuri android
 */
package com.yuri.onscripter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class SmbVirtualFs {

    public static final String ROOT_PREFIX = "/onsyuri_smb/";
    private static final String LOGTAG = "## onsyuri_android";
    // files smaller than this are downloaded silently, they are fast anyway
    private static final long PROGRESS_MIN_SIZE = 1024 * 1024;

    private final Context m_context;
    private final File m_cacheRoot;
    private final String m_root;
    private final String m_host;
    private final int m_port;
    private final String m_share;
    private final String m_base;
    private final String m_user;
    private final String m_password;

    private SmbClient m_client;

    // on-demand download progress, shown above the game surface
    private Handler m_progressHandler;
    private Toast m_progressToast;
    private long m_lastProgressTime;

    public SmbVirtualFs(Context context, String token, String host, int port, String share,
                        String remoteBase, String user, String password) {
        m_context = context;
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        m_cacheRoot = new File(base, "smb_virtual/" + safe(token));
        m_root = ROOT_PREFIX + token;
        m_host = host == null ? "" : host;
        m_port = port <= 0 ? GameSources.DEFAULT_SMB_PORT : port;
        m_share = share == null ? "" : share;
        m_base = remoteBase == null ? "" : remoteBase;
        m_user = user == null ? "" : user;
        m_password = password == null ? "" : password;
    }

    /** the absolute path prefix handled by this instance */
    public String getRoot() {
        return m_root;
    }

    public boolean isVirtual(String path) {
        if (path == null) return false;
        return path.equals(m_root) || path.startsWith(m_root + "/");
    }

    // ------------------------------------------------------------------ fd

    /** mode: 0 = read, 1 = write (same meaning as ONScripter.getFD) */
    public synchronized int openFd(String path, int mode) {
        String rel = relative(path);
        if (rel == null) return -1;
        File local = localFile(rel);

        try {
            if (mode == 1) {
                if (!ensureParent(local)) return -1;
                return ParcelFileDescriptor.open(local,
                        ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE
                                | ParcelFileDescriptor.MODE_READ_WRITE).detachFd();
            }

            if (!local.isFile()) {
                if (!downloadWithRetry(rel, local)) return -1;
            }
            return ParcelFileDescriptor.open(local,
                    ParcelFileDescriptor.MODE_READ_ONLY).detachFd();
        } catch (Exception e) {
            Log.w(LOGTAG, "smb virtual open failed: " + path + " : " + e.getMessage());
            return -1;
        }
    }

    public synchronized int mkdir(String path) {
        String rel = relative(path);
        if (rel == null) return -1;
        if (rel.length() == 0) return 0;
        File dir = localFile(rel);
        if (dir.isDirectory()) return 0;
        return dir.mkdirs() ? 0 : -1;
    }

    // -------------------------------------------------------------- cache

    private String relative(String path) {
        String p = path.replace('\\', '/');
        if (!p.startsWith(m_root)) return null;
        p = p.substring(m_root.length());
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) return null;
        return p;
    }

    private File localFile(String rel) {
        return new File(m_cacheRoot, rel);
    }

    private boolean ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent == null) return false;
        return parent.isDirectory() || parent.mkdirs();
    }

    private boolean downloadWithRetry(String rel, File local) {
        try {
            download(rel, local);
            return true;
        } catch (IOException first) {
            // a missing file is normal, only retry when the session itself is broken
            if (m_client != null && m_client.isConnected() && m_client.hasTree()) {
                Log.w(LOGTAG, "smb virtual file unavailable: " + rel + " : " + first.getMessage());
                return false;
            }
            closeClient();
            try {
                download(rel, local);
                return true;
            } catch (IOException second) {
                Log.w(LOGTAG, "smb virtual download failed: " + rel + " : " + second.getMessage());
                return false;
            }
        }
    }

    private void download(String rel, File local) throws IOException {
        if (!ensureParent(local)) throw new IOException("cannot create cache directory");
        File tmp = new File(local.getAbsolutePath() + ".part");
        try {
            SmbClient client = client();
            final String displayName = new File(rel).getName();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                client.download(remotePath(rel), out, (done, total) ->
                        updateProgress(displayName, done, total));
            }
            if (local.exists() && !local.delete()) throw new IOException("cannot replace " + local.getName());
            if (!tmp.renameTo(local)) throw new IOException("cannot rename " + tmp.getName());
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            finishProgress();
        }
    }

    // ------------------------------------------------------ download progress

    private void updateProgress(final String name, final long done, final long total) {
        if (total < PROGRESS_MIN_SIZE && done < PROGRESS_MIN_SIZE) return;

        long now = System.currentTimeMillis();
        if (now - m_lastProgressTime < 300) return;
        m_lastProgressTime = now;

        final String text;
        if (total > 0) {
            int percent = (int) (done * 100 / total);
            if (percent > 100) percent = 100;
            text = String.format(Locale.US, "SMB %s  %d%%  (%.1f/%.1f MB)",
                    name, percent, done / 1048576.0, total / 1048576.0);
        } else {
            text = String.format(Locale.US, "SMB %s  %.1f MB", name, done / 1048576.0);
        }

        if (m_progressHandler == null) m_progressHandler = new Handler(Looper.getMainLooper());
        m_progressHandler.post(() -> {
            try {
                if (m_progressToast == null) {
                    m_progressToast = Toast.makeText(m_context, text, Toast.LENGTH_SHORT);
                } else {
                    m_progressToast.setText(text);
                }
                m_progressToast.show();
            } catch (Exception ignored) {}
        });
    }

    private void finishProgress() {
        if (m_progressHandler == null) return;
        m_progressHandler.post(() -> {
            if (m_progressToast != null) {
                m_progressToast.cancel();
                m_progressToast = null;
            }
        });
    }

    private String remotePath(String rel) {
        if (m_base.length() == 0) return rel;
        return SmbClient.join(m_base, rel);
    }

    private SmbClient client() throws IOException {
        if (m_client != null && m_client.isConnected() && m_client.hasTree()) return m_client;
        closeClient();
        m_client = new SmbClient();
        m_client.connect(m_host, m_port, m_share, m_user, m_password);
        return m_client;
    }

    private void closeClient() {
        if (m_client != null) {
            m_client.close();
            m_client = null;
        }
    }

    public synchronized void close() {
        closeClient();
        finishProgress();
    }

    private static String safe(String token) {
        if (token == null || token.length() == 0) return "game";
        StringBuilder sb = new StringBuilder(token.length());
        String invalid = "\\/:*?\"<>|";
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (invalid.indexOf(c) >= 0 || c < 0x20) sb.append('_');
            else sb.append(c);
        }
        return sb.toString();
    }
}
