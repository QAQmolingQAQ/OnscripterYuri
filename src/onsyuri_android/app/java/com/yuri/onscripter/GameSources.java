/**
 * Saved game directory sources (app storage, local path, SAF tree and SMB).
 * Multiple sources can be enabled at the same time.
 *
 * v0.7.8, for OnscripterYuri android
 */
package com.yuri.onscripter;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class GameSources {

    public static final String PREF_KEY = "gamesources";
    public static final String TYPE_APP = "app";
    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_SAF = "saf";
    public static final String TYPE_SMB = "smb";
    public static final int DEFAULT_SMB_PORT = 445;

    private GameSources() {}

    public static class Source {
        public String type = TYPE_LOCAL;
        public String name = "";
        public String path = "";
        public String user = "";
        public String password = "";
        public int port = DEFAULT_SMB_PORT;
        public boolean enabled = true;

        public boolean isSmb() { return TYPE_SMB.equals(type); }
        public boolean isApp() { return TYPE_APP.equals(type); }
        public boolean isSaf() { return TYPE_SAF.equals(type); }

        public String displayName() {
            if (name != null && name.length() > 0) return name;
            if (isApp()) return "app storage";
            if (isSmb()) return "smb " + smbHost(this) + "/" + smbShare(this);
            if (isSaf()) return "saf " + path;
            return path;
        }

        public String displayPath() {
            switch (type) {
                case TYPE_APP: return "app storage";
                case TYPE_SMB: return "smb://" + hostPort(this) + "/" + pathAfterHost(this);
                default: return path;
            }
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type);
                o.put("name", name);
                o.put("path", path);
                o.put("user", user);
                o.put("password", password);
                o.put("port", port);
                o.put("enabled", enabled);
            } catch (JSONException ignored) {}
            return o;
        }

        public static Source fromJson(JSONObject o) {
            Source s = new Source();
            s.type = optString(o, "type", TYPE_LOCAL);
            s.name = optString(o, "name", "");
            s.path = optString(o, "path", "");
            s.user = optString(o, "user", "");
            s.password = optString(o, "password", "");
            s.port = optInt(o, "port", DEFAULT_SMB_PORT);
            s.enabled = optBool(o, "enabled", true);
            return s;
        }

        public Source copy() {
            return fromJson(toJson());
        }
    }

    public static Source newSource(String type, String name, String path) {
        Source s = new Source();
        s.type = type;
        s.name = name == null ? "" : name;
        s.path = path == null ? "" : path;
        s.enabled = true;
        return s;
    }

    public static List<Source> load(SharedPreferences prefs) {
        List<Source> sources = new ArrayList<>();
        String raw = prefs.getString(PREF_KEY, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) sources.add(Source.fromJson(o));
                }
                return sources;
            } catch (JSONException ignored) {}
        }

        // migrate the old single game dir and saf uri settings
        Source app = newSource(TYPE_APP, "app storage", "");
        sources.add(app);
        String oldDir = prefs.getString("gamedir", null);
        if (oldDir != null && oldDir.length() > 0) {
            sources.add(newSource(TYPE_LOCAL, baseName(oldDir), oldDir));
        }
        String oldUri = prefs.getString("gameuri", null);
        if (oldUri != null && oldUri.length() > 0) {
            sources.add(newSource(TYPE_SAF, "saf", oldUri));
        }
        save(prefs, sources);
        return sources;
    }

    public static void save(SharedPreferences prefs, List<Source> sources) {
        JSONArray arr = new JSONArray();
        for (Source s : sources) arr.put(s.toJson());
        prefs.edit().putString(PREF_KEY, arr.toString()).apply();
    }

    // ------------------------------------------------------------ smb helpers

    public static String cleanAddress(String address) {
        String a = address == null ? "" : address.trim();
        if (a.toLowerCase().startsWith("smb://")) a = a.substring(6);
        while (a.startsWith("/")) a = a.substring(1);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /** "host/share/sub" -> "host" */
    public static String smbHost(Source s) {
        String a = cleanAddress(s.path);
        int slash = a.indexOf('/');
        return slash < 0 ? a : a.substring(0, slash);
    }

    /** "host/share/sub" -> "share" */
    public static String smbShare(Source s) {
        String after = pathAfterHost(s);
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }

    /** "host/share/sub" -> "sub" (path inside the share) */
    public static String smbSubPath(Source s) {
        String after = pathAfterHost(s);
        int slash = after.indexOf('/');
        return slash < 0 ? "" : after.substring(slash + 1);
    }

    /** "host/share/sub" -> "share/sub" */
    public static String pathAfterHost(Source s) {
        String a = cleanAddress(s.path);
        int slash = a.indexOf('/');
        return slash < 0 ? "" : a.substring(slash + 1);
    }

    public static String hostPort(Source s) {
        return smbHost(s) + ":" + s.port;
    }

    private static String baseName(String path) {
        if (path == null) return "";
        String p = path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int idx = p.lastIndexOf('/');
        if (idx >= 0) return p.substring(idx + 1);
        return p;
    }

    private static String optString(JSONObject o, String key, String def) {
        String v = o.optString(key, def);
        return v == null ? def : v;
    }

    private static int optInt(JSONObject o, String key, int def) {
        return o.optInt(key, def);
    }

    private static boolean optBool(JSONObject o, String key, boolean def) {
        return o.optBoolean(key, def);
    }
}
