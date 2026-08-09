package com.redline.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LibraryStore {
    private static final String PREFS = "redline_library";
    private static final String LIBRARY = "library";
    private static final String PLAYLISTS = "playlists";
    private static final String QUEUE = "queue";
    private static final String LAST_URI = "last_uri";
    private static final String LAST_POSITION = "last_position";
    private static final String SHUFFLE = "shuffle";
    private static final String REPEAT = "repeat";
    private static final String LAST_VALIDATION = "last_validation";
    private static final long VALIDATION_INTERVAL_MS = 30_000L;

    private LibraryStore() { }

    public static synchronized ArrayList<Track> getLibrary(Context context) {
        return readTracks(context, LIBRARY);
    }

    public static synchronized int importUris(Context context, List<Uri> uris) {
        ArrayList<Track> tracks = getLibrary(context);
        Set<String> known = new HashSet<>();
        for (Track track : tracks) known.add(track.uri);
        int imported = 0;
        for (Uri uri : uris) {
            if (uri == null || known.contains(uri.toString())) continue;
            Track track = readMetadata(context, uri);
            if (track == null) continue;
            tracks.add(track);
            known.add(track.uri);
            imported++;
        }
        if (imported > 0) {
            sortTracks(tracks);
            writeTracks(context, LIBRARY, tracks);
        }
        return imported;
    }

    public static synchronized int validateLibrary(Context context) {
        long now = System.currentTimeMillis();
        if (now - prefs(context).getLong(LAST_VALIDATION, 0L) < VALIDATION_INTERVAL_MS) return 0;
        ArrayList<Track> tracks = getLibrary(context);
        ArrayList<Track> valid = new ArrayList<>();
        int removed = 0;
        for (Track track : tracks) {
            if (isReadable(context.getContentResolver(), track.uri)) valid.add(track); else removed++;
        }
        if (removed > 0) {
            writeTracks(context, LIBRARY, valid);
            removeMissingFromPlaylists(context, valid);
        }
        prefs(context).edit().putLong(LAST_VALIDATION, now).apply();
        return removed;
    }

    public static synchronized void removeUri(Context context, String uri) {
        ArrayList<Track> tracks = getLibrary(context);
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (tracks.get(i).uri.equals(uri)) tracks.remove(i);
        }
        writeTracks(context, LIBRARY, tracks);
        removeUriFromAllPlaylists(context, uri);
    }

    public static synchronized Track find(Context context, String uri) {
        if (uri == null) return null;
        for (Track track : getLibrary(context)) if (uri.equals(track.uri)) return track;
        return null;
    }

    public static synchronized ArrayList<Track> tracksForUris(Context context, List<String> uris) {
        ArrayList<Track> library = getLibrary(context);
        ArrayList<Track> result = new ArrayList<>();
        for (String uri : uris) {
            for (Track track : library) {
                if (track.uri.equals(uri)) {
                    result.add(track);
                    break;
                }
            }
        }
        return result;
    }

    public static synchronized ArrayList<String> getQueueUris(Context context) {
        return readStrings(context, QUEUE);
    }

    public static synchronized void saveQueue(Context context, List<Track> tracks) {
        writeStrings(context, QUEUE, trackUris(tracks));
    }

    public static synchronized String getLastUri(Context context) {
        return prefs(context).getString(LAST_URI, null);
    }

    public static synchronized long getLastPosition(Context context) {
        return prefs(context).getLong(LAST_POSITION, 0L);
    }

    public static synchronized boolean isShuffle(Context context) {
        return prefs(context).getBoolean(SHUFFLE, false);
    }

    public static synchronized int getRepeat(Context context) {
        return prefs(context).getInt(REPEAT, 0);
    }

    public static synchronized void savePlayback(Context context, String uri, long position, boolean shuffle, int repeat) {
        prefs(context).edit()
                .putString(LAST_URI, uri)
                .putLong(LAST_POSITION, Math.max(0L, position))
                .putBoolean(SHUFFLE, shuffle)
                .putInt(REPEAT, repeat)
                .apply();
    }

    public static synchronized ArrayList<String> getPlaylistNames(Context context) {
        ArrayList<String> names = new ArrayList<>();
        try {
            JSONArray playlists = new JSONArray(prefs(context).getString(PLAYLISTS, "[]"));
            for (int i = 0; i < playlists.length(); i++) names.add(playlists.getJSONObject(i).optString("name"));
        } catch (JSONException ignored) { }
        return names;
    }

    public static synchronized void createPlaylist(Context context, String name) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) return;
        if (getPlaylistNames(context).contains(cleanName)) return;
        JSONArray playlists = readPlaylists(context);
        JSONObject playlist = new JSONObject();
        try {
            playlist.put("name", cleanName);
            playlist.put("uris", new JSONArray());
            playlists.put(playlist);
        } catch (JSONException ignored) { }
        writePlaylists(context, playlists);
    }

    public static synchronized ArrayList<Track> getPlaylist(Context context, String name) {
        ArrayList<String> uris = new ArrayList<>();
        for (JSONObject playlist : playlistObjects(context)) {
            if (name.equals(playlist.optString("name"))) {
                JSONArray values = playlist.optJSONArray("uris");
                if (values != null) for (int i = 0; i < values.length(); i++) uris.add(values.optString(i));
                break;
            }
        }
        return tracksForUris(context, uris);
    }

    public static synchronized void addToPlaylist(Context context, String name, String uri) {
        JSONArray playlists = readPlaylists(context);
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist == null || !name.equals(playlist.optString("name"))) continue;
            JSONArray uris = playlist.optJSONArray("uris");
            if (uris == null) uris = new JSONArray();
            if (!contains(uris, uri)) uris.put(uri);
            try { playlist.put("uris", uris); } catch (JSONException ignored) { }
            break;
        }
        writePlaylists(context, playlists);
    }

    public static synchronized void removeFromPlaylist(Context context, String name, String uri) {
        JSONArray playlists = readPlaylists(context);
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist == null || !name.equals(playlist.optString("name"))) continue;
            JSONArray oldUris = playlist.optJSONArray("uris");
            JSONArray newUris = new JSONArray();
            if (oldUris != null) for (int j = 0; j < oldUris.length(); j++) if (!uri.equals(oldUris.optString(j))) newUris.put(oldUris.optString(j));
            try { playlist.put("uris", newUris); } catch (JSONException ignored) { }
            break;
        }
        writePlaylists(context, playlists);
    }

    public static synchronized void deletePlaylist(Context context, String name) {
        JSONArray playlists = readPlaylists(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist != null && !name.equals(playlist.optString("name"))) kept.put(playlist);
        }
        writePlaylists(context, kept);
    }

    public static ArrayList<String> trackUris(List<Track> tracks) {
        ArrayList<String> uris = new ArrayList<>();
        for (Track track : tracks) uris.add(track.uri);
        return uris;
    }

    private static Track readMetadata(Context context, Uri uri) {
        String fallback = displayName(context.getContentResolver(), uri);
        String title = fallback;
        String artist = "Unknown artist";
        String album = "Unknown album";
        long duration = 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            title = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), fallback);
            artist = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), artist);
            album = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), album);
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationText != null) duration = Long.parseLong(durationText);
        } catch (Exception ignored) {
            if (!isReadable(context.getContentResolver(), uri)) return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        return new Track(uri.toString(), removeExtension(title), artist, album, folderName(uri), duration);
    }

    private static boolean isReadable(ContentResolver resolver, String uri) {
        try (Cursor cursor = resolver.query(Uri.parse(uri), new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "Untitled song" : last;
    }

    private static String folderName(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return "Imported files";
        String[] parts = path.split("/");
        return parts.length > 1 ? parts[parts.length - 2] : "Imported files";
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static ArrayList<Track> readTracks(Context context, String key) {
        ArrayList<Track> tracks = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null && !object.optString("uri").isEmpty()) tracks.add(Track.fromJson(object));
            }
        } catch (JSONException ignored) { }
        return tracks;
    }

    private static void writeTracks(Context context, String key, List<Track> tracks) {
        JSONArray array = new JSONArray();
        for (Track track : tracks) try { array.put(track.toJson()); } catch (JSONException ignored) { }
        prefs(context).edit().putString(key, array.toString()).apply();
    }

    private static ArrayList<String> readStrings(Context context, String key) {
        ArrayList<String> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) if (!array.optString(i).isEmpty()) values.add(array.optString(i));
        } catch (JSONException ignored) { }
        return values;
    }

    private static void writeStrings(Context context, String key, List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        prefs(context).edit().putString(key, array.toString()).apply();
    }

    private static JSONArray readPlaylists(Context context) {
        try { return new JSONArray(prefs(context).getString(PLAYLISTS, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static ArrayList<JSONObject> playlistObjects(Context context) {
        ArrayList<JSONObject> result = new ArrayList<>();
        JSONArray playlists = readPlaylists(context);
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist != null) result.add(playlist);
        }
        return result;
    }

    private static void writePlaylists(Context context, JSONArray playlists) {
        prefs(context).edit().putString(PLAYLISTS, playlists.toString()).apply();
    }

    private static void removeMissingFromPlaylists(Context context, List<Track> valid) {
        Set<String> allowed = new HashSet<>(trackUris(valid));
        JSONArray playlists = readPlaylists(context);
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist == null) continue;
            JSONArray oldUris = playlist.optJSONArray("uris");
            JSONArray newUris = new JSONArray();
            if (oldUris != null) for (int j = 0; j < oldUris.length(); j++) if (allowed.contains(oldUris.optString(j))) newUris.put(oldUris.optString(j));
            try { playlist.put("uris", newUris); } catch (JSONException ignored) { }
        }
        writePlaylists(context, playlists);
    }

    private static void removeUriFromAllPlaylists(Context context, String uri) {
        ArrayList<Track> valid = getLibrary(context);
        removeMissingFromPlaylists(context, valid);
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true;
        return false;
    }

    private static void sortTracks(ArrayList<Track> tracks) {
        Collections.sort(tracks, Comparator.comparing(track -> track.title.toLowerCase()));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
