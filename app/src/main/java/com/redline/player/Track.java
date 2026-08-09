package com.redline.player;

import org.json.JSONException;
import org.json.JSONObject;

public final class Track {
    public final String uri;
    public final String title;
    public final String artist;
    public final String album;
    public final String folder;
    public final long duration;

    public Track(String uri, String title, String artist, String album, String folder, long duration) {
        this.uri = uri;
        this.title = clean(title, "Unknown title");
        this.artist = clean(artist, "Unknown artist");
        this.album = clean(album, "Unknown album");
        this.folder = clean(folder, "Imported files");
        this.duration = Math.max(0L, duration);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("uri", uri);
        object.put("title", title);
        object.put("artist", artist);
        object.put("album", album);
        object.put("folder", folder);
        object.put("duration", duration);
        return object;
    }

    public static Track fromJson(JSONObject object) {
        return new Track(
                object.optString("uri"),
                object.optString("title"),
                object.optString("artist"),
                object.optString("album"),
                object.optString("folder"),
                object.optLong("duration"));
    }

    public String secondaryText() {
        return artist + "  /  " + album;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
