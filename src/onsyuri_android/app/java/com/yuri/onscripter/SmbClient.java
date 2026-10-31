/**
 * SMB2/SMB3 client wrapper for OnscripterYuri.
 *
 * The protocol implementation is the open source SMBJ library:
 *   https://github.com/hierynomus/smbj (Apache License 2.0)
 * SMBJ supports SMB 2.0.2 - 3.1.1, NTLMv2, signing and encryption, and has
 * an Android compatibility layer.
 *
 * v0.7.8, for OnscripterYuri android
 */
package com.yuri.onscripter;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SmbClient {

    public static final int DEFAULT_PORT = 445;
    private static final long STATUS_ACCESS_DENIED = 0xC0000022L;
    private static final int BUFFER_SIZE = 1 << 16;

    public static class SmbEntry {
        public final String name;
        public final boolean isDirectory;
        public final long size;
        public final long mtime; // epoch seconds, 0 if unknown

        public SmbEntry(String name, boolean isDirectory, long size, long mtime) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
            this.mtime = mtime;
        }
    }

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private SMBClient m_client;
    private Connection m_connection;
    private Session m_session;
    private DiskShare m_share;

    private String m_host = "";
    private int m_port = DEFAULT_PORT;
    private String m_shareName = "";
    private String m_user = "";
    private String m_password = "";
    private String m_domain = "";
    private boolean m_userHasDomain = false;
    private String m_dialectName = "";

    public SmbClient() {}

    public String getHost() { return m_host; }
    public String getShare() { return m_shareName; }
    public String getDialectName() { return m_dialectName; }
    public boolean isConnected() { return m_connection != null; }
    public boolean hasTree() { return m_share != null; }

    // ------------------------------------------------------------- connect

    /**
     * Connect, authenticate and open the share.
     * Use "DOMAIN\\user" or "user@domain" style for a domain account.
     */
    public void connect(String host, int port, String share, String user, String pass) throws IOException {
        close();
        m_host = host == null ? "" : host.trim();
        m_port = port <= 0 ? DEFAULT_PORT : port;
        m_shareName = cleanShare(share);
        m_password = pass == null ? "" : pass;
        parseUser(user);

        try {
            openAndLogin();
        } catch (RuntimeException e) {
            // windows microsoft accounts use the "MicrosoftAccount" domain. if the
            // login was accepted but the share was denied, retry once with it.
            if (!m_userHasDomain && isAccessDenied(e)) {
                close();
                m_domain = "MicrosoftAccount";
                try {
                    openAndLogin();
                } catch (RuntimeException retryError) {
                    throw wrap(retryError);
                }
            } else {
                throw wrap(e);
            }
        }
    }

    /** connect with an address of the form "host/share" */
    public void connect(String address, String user, String pass) throws IOException {
        String host = address == null ? "" : address;
        String share = "";
        int slash = host.indexOf('/');
        if (slash >= 0) {
            share = host.substring(slash + 1);
            host = host.substring(0, slash);
        }
        connect(host, DEFAULT_PORT, share, user, pass);
    }

    private void openAndLogin() throws IOException {
        SmbConfig config = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build();
        m_client = new SMBClient(config);
        m_connection = m_client.connect(m_host, m_port);
        m_session = m_connection.authenticate(
                new AuthenticationContext(m_user, m_password.toCharArray(), m_domain));
        m_share = (DiskShare) m_session.connectShare(m_shareName);
        m_dialectName = dialectName(m_connection.getNegotiatedProtocol().getDialect());
    }

    private static String dialectName(SMB2Dialect dialect) {
        if (dialect == null) return "SMB2";
        switch (dialect) {
            case SMB_2_0_2: return "SMB 2.0.2";
            case SMB_2_1: return "SMB 2.1";
            case SMB_3_0: return "SMB 3.0";
            case SMB_3_0_2: return "SMB 3.0.2";
            case SMB_3_1_1: return "SMB 3.1.1";
            default: return dialect.name();
        }
    }

    private void parseUser(String user) {
        String u = user == null ? "" : user.trim();
        m_domain = "";
        m_user = u;
        m_userHasDomain = false;
        int idx = u.indexOf('\\');
        if (idx > 0) {
            m_domain = u.substring(0, idx);
            m_user = u.substring(idx + 1);
            m_userHasDomain = true;
        }
        if (m_user.startsWith("\\")) m_user = m_user.substring(1);
    }

    private static String cleanShare(String share) {
        String s = share == null ? "" : share.trim();
        while (s.startsWith("\\") || s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("\\") || s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public void close() {
        if (m_share != null) {
            try { m_share.close(); } catch (Exception ignored) {}
            m_share = null;
        }
        if (m_session != null) {
            try { m_session.close(); } catch (Exception ignored) {}
            m_session = null;
        }
        if (m_connection != null) {
            try { m_connection.close(); } catch (Exception ignored) {}
            m_connection = null;
        }
        if (m_client != null) {
            try { m_client.close(); } catch (Exception ignored) {}
            m_client = null;
        }
        m_dialectName = "";
    }

    // ---------------------------------------------------------- list files

    public List<SmbEntry> list(String path) throws IOException {
        checkShare();
        try {
            String p = normalize(path);
            List<FileIdBothDirectoryInformation> infos = m_share.list(p, "*");
            List<SmbEntry> result = new ArrayList<>(infos.size());
            for (FileIdBothDirectoryInformation info : infos) {
                String name = info.getFileName();
                if (name == null || name.equals(".") || name.equals("..")) continue;
                boolean dir = (info.getFileAttributes()
                        & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                long mtime = info.getLastWriteTime() == null ? 0
                        : info.getLastWriteTime().toEpochMillis() / 1000L;
                result.add(new SmbEntry(name, dir, info.getEndOfFile(), mtime));
            }
            return result;
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    public boolean exists(String path) {
        if (m_share == null) return false;
        try {
            return m_share.fileExists(normalize(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public long getSize(String path) throws IOException {
        checkShare();
        com.hierynomus.smbj.share.File file = null;
        try {
            file = openRead(normalize(path));
            return file.getLength();
        } catch (RuntimeException e) {
            throw wrap(e);
        } finally {
            if (file != null) file.close();
        }
    }

    public long download(String path, OutputStream os, ProgressListener listener) throws IOException {
        checkShare();
        com.hierynomus.smbj.share.File file = null;
        try {
            file = openRead(normalize(path));
            long size = file.getLength();
            long total = 0;
            InputStream in = file.getInputStream();
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    os.write(buffer, 0, n);
                    total += n;
                    if (listener != null) listener.onProgress(total, size);
                }
            } finally {
                in.close();
            }
            return total;
        } catch (RuntimeException e) {
            throw wrap(e);
        } finally {
            if (file != null) file.close();
        }
    }

    private com.hierynomus.smbj.share.File openRead(String path) {
        return m_share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
    }

    private void checkShare() throws IOException {
        if (m_share == null) throw new IOException("not connected to a share");
    }

    // --------------------------------------------------------------- helpers

    private static IOException wrap(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.length() == 0) message = e.toString();
        return new IOException(message, e);
    }

    private static boolean isAccessDenied(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof SMBApiException) {
                if (((SMBApiException) t).getStatusCode() == STATUS_ACCESS_DENIED) return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static final String EMPTY = "";
    private static final String SLASH = "/";

    public static String normalize(String path) {
        if (path == null) return EMPTY;
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            sb.append(c == '/' ? '\\' : c);
        }
        String p = sb.toString();
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p;
    }

    public static String parent(String path) {
        if (path == null) return EMPTY;
        String p = path;
        while (p.endsWith(SLASH)) p = p.substring(0, p.length() - 1);
        int idx = p.lastIndexOf(SLASH);
        return idx < 0 ? EMPTY : p.substring(0, idx);
    }

    public static String join(String base, String name) {
        if (base == null || base.length() == 0) return name;
        if (base.endsWith(SLASH)) return base + name;
        return base + SLASH + name;
    }

}
