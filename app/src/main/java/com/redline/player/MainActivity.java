package com.redline.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 41;
    private static final int SCREEN_LIBRARY = 0;
    private static final int SCREEN_PLAYLISTS = 1;
    private static final int SCREEN_QUEUE = 2;
    private static final int SCREEN_NOW_PLAYING = 3;

    private final ArrayList<Track> emptyTracks = new ArrayList<>();
    private int screen = SCREEN_LIBRARY;
    private int libraryTab;
    private String groupFilter;
    private String currentPlaylist;

    private LinearLayout screenContainer;
    private LinearLayout miniPlayer;
    private LinearLayout navigation;
    private TextView pageTitle;
    private TextView pageAction;
    private TextView miniTitle;
    private TextView miniMeta;
    private TextView miniPlay;
    private TextView nowTitle;
    private TextView nowArtist;
    private TextView nowElapsed;
    private TextView nowDuration;
    private TextView nowPlay;
    private SeekBar nowSeek;
    private ImageView nowArt;
    private TextView nowArtFallback;

    private String stateUri;
    private String stateTitle = "";
    private String stateArtist = "";
    private String stateAlbum = "";
    private long statePosition;
    private long stateDuration;
    private boolean statePlaying;
    private boolean stateShuffle;
    private int stateRepeat;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            stateUri = intent.getStringExtra(PlayerService.EXTRA_URI);
            stateTitle = safe(intent.getStringExtra("title"));
            stateArtist = safe(intent.getStringExtra("artist"));
            stateAlbum = safe(intent.getStringExtra("album"));
            statePosition = intent.getLongExtra("position", 0L);
            stateDuration = intent.getLongExtra("duration", 0L);
            statePlaying = intent.getBooleanExtra("playing", false);
            stateShuffle = intent.getBooleanExtra("shuffle", false);
            stateRepeat = intent.getIntExtra("repeat", 0);
            updateMiniPlayer();
            updateNowPlaying();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
        registerStateReceiver();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        restoreDisplayedState();
        PlayerService.ensureStarted(this);
        LibraryStore.validateLibrary(this);
        renderScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        if (LibraryStore.validateLibrary(this) > 0) renderScreen();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), 0);
        root.setBackgroundColor(color(R.color.ink));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("REDLINE", 22, Color.WHITE, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(52), 1));
        pageTitle = label("LIBRARY", 10, color(R.color.redline), Typeface.BOLD);
        pageTitle.setGravity(Gravity.CENTER);
        pageTitle.setLetterSpacing(.18f);
        header.addView(pageTitle, new LinearLayout.LayoutParams(dp(96), dp(52)));
        pageAction = label("+ ADD", 11, Color.WHITE, Typeface.BOLD);
        pageAction.setGravity(Gravity.CENTER);
        pageAction.setBackground(round(color(R.color.panel_light), dp(8)));
        pageAction.setOnClickListener(v -> openPicker());
        header.addView(pageAction, new LinearLayout.LayoutParams(dp(72), dp(42)));
        root.addView(header);

        View rule = new View(this);
        rule.setBackgroundColor(color(R.color.redline));
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(2)));

        screenContainer = new LinearLayout(this);
        screenContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(screenContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = buildMiniPlayer();
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, dp(72)));
        navigation = buildNavigation();
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(54)));
        setContentView(root);
    }

    private LinearLayout buildMiniPlayer() {
        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(10), dp(8), dp(8), dp(8));
        mini.setBackground(round(color(R.color.panel), dp(12)));
        TextView art = label("R", 24, color(R.color.redline), Typeface.BOLD);
        art.setGravity(Gravity.CENTER);
        art.setBackground(round(color(R.color.ink), dp(8)));
        mini.addView(art, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        miniTitle = label("NOTHING PLAYING", 14, Color.WHITE, Typeface.BOLD);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTitle.setSingleLine(true);
        miniMeta = label("Choose a song to start", 11, color(R.color.muted), Typeface.NORMAL);
        miniMeta.setEllipsize(TextUtils.TruncateAt.END);
        miniMeta.setSingleLine(true);
        text.addView(miniTitle, new LinearLayout.LayoutParams(-1, 0, 1));
        text.addView(miniMeta, new LinearLayout.LayoutParams(-1, 0, 1));
        mini.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
        miniPlay = label("PLAY", 11, Color.WHITE, Typeface.BOLD);
        miniPlay.setGravity(Gravity.CENTER);
        miniPlay.setContentDescription("Play or pause");
        miniPlay.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_TOGGLE));
        mini.addView(miniPlay, new LinearLayout.LayoutParams(dp(62), -1));
        mini.setOnClickListener(v -> {
            if (stateUri != null) { screen = SCREEN_NOW_PLAYING; renderScreen(); }
        });
        return mini;
    }

    private LinearLayout buildNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(6), 0, dp(4));
        nav.addView(navItem("LIBRARY", SCREEN_LIBRARY), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("PLAYLISTS", SCREEN_PLAYLISTS), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("QUEUE", SCREEN_QUEUE), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("NOW PLAYING", SCREEN_NOW_PLAYING), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private TextView navItem(String text, int target) {
        TextView item = label(text, 9, color(R.color.muted), Typeface.BOLD);
        item.setGravity(Gravity.CENTER);
        item.setLetterSpacing(.08f);
        item.setOnClickListener(v -> {
            screen = target;
            if (target == SCREEN_LIBRARY) currentPlaylist = null;
            renderScreen();
        });
        return item;
    }

    private void renderScreen() {
        screenContainer.removeAllViews();
        pageAction.setVisibility(screen == SCREEN_NOW_PLAYING ? View.GONE : View.VISIBLE);
        miniPlayer.setVisibility(screen == SCREEN_NOW_PLAYING ? View.GONE : View.VISIBLE);
        if (screen == SCREEN_LIBRARY) renderLibrary();
        else if (screen == SCREEN_PLAYLISTS) renderPlaylists();
        else if (screen == SCREEN_QUEUE) renderQueue();
        else renderNowPlayingScreen();
        updateNavigationColors();
        updateMiniPlayer();
    }

    private void renderLibrary() {
        pageTitle.setText("LIBRARY");
        pageAction.setText("+ ADD");
        pageAction.setOnClickListener(v -> openPicker());
        LinearLayout heading = heading("YOUR MUSIC", LibraryStore.getLibrary(this).size() + " songs");
        screenContainer.addView(heading, new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, 0, 0, dp(8));
        String[] names = {"SONGS", "ALBUMS", "ARTISTS", "FOLDERS"};
        for (int i = 0; i < names.length; i++) {
            TextView tab = label(names[i], 10, i == libraryTab ? Color.WHITE : color(R.color.muted), Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(i == libraryTab ? round(color(R.color.redline_dark), dp(6)) : round(color(R.color.panel), dp(6)));
            final int selected = i;
            tab.setOnClickListener(v -> { libraryTab = selected; groupFilter = null; renderScreen(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            params.setMargins(i == 0 ? 0 : dp(4), 0, i == names.length - 1 ? 0 : dp(4), 0);
            tabs.addView(tab, params);
        }
        screenContainer.addView(tabs);

        if (groupFilter != null) {
            TextView back = actionText("<  ALL " + tabName() + "", () -> { groupFilter = null; renderScreen(); });
            screenContainer.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
            addTrackList(filteredTracks(), true, null);
        } else if (libraryTab == 0) {
            addTrackList(LibraryStore.getLibrary(this), true, null);
        } else {
            addGroupList(groupedKeys(libraryTab));
        }
    }

    private void renderPlaylists() {
        pageTitle.setText("PLAYLISTS");
        pageAction.setText("+ NEW");
        pageAction.setOnClickListener(v -> showCreatePlaylistDialog());
        ArrayList<String> names = LibraryStore.getPlaylistNames(this);
        LinearLayout heading = heading("YOUR PLAYLISTS", names.size() + " playlists");
        screenContainer.addView(heading, new LinearLayout.LayoutParams(-1, dp(62)));
        if (currentPlaylist != null) {
            TextView back = actionText("<  ALL PLAYLISTS", () -> { currentPlaylist = null; renderScreen(); });
            screenContainer.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
            TextView title = label(currentPlaylist, 22, Color.WHITE, Typeface.BOLD);
            title.setPadding(0, dp(4), 0, dp(12));
            screenContainer.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
            addTrackList(LibraryStore.getPlaylist(this, currentPlaylist), true, currentPlaylist);
            return;
        }
        if (names.isEmpty()) {
            addEmpty("NO PLAYLISTS YET", "Create one, then add songs from the library.");
            return;
        }
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new PlaylistAdapter(names));
        list.setOnItemClickListener((parent, view, position, id) -> { currentPlaylist = names.get(position); renderScreen(); });
        list.setOnItemLongClickListener((parent, view, position, id) -> { showPlaylistMenu(names.get(position)); return true; });
        screenContainer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void renderQueue() {
        pageTitle.setText("QUEUE");
        pageAction.setText("CLEAR");
        pageAction.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_CLEAR_QUEUE));
        ArrayList<Track> tracks = LibraryStore.tracksForUris(this, LibraryStore.getQueueUris(this));
        LinearLayout heading = heading("UP NEXT", tracks.size() + " queued");
        screenContainer.addView(heading, new LinearLayout.LayoutParams(-1, dp(62)));
        LinearLayout modes = new LinearLayout(this);
        TextView shuffle = modeButton("SHUFFLE " + (stateShuffle ? "ON" : "OFF"));
        TextView repeat = modeButton("REPEAT " + repeatName());
        shuffle.setOnClickListener(v -> sendValue(PlayerService.ACTION_SHUFFLE, !stateShuffle));
        repeat.setOnClickListener(v -> sendInt(PlayerService.ACTION_REPEAT, (stateRepeat + 1) % 3));
        modes.addView(shuffle, new LinearLayout.LayoutParams(0, dp(38), 1));
        modes.addView(repeat, new LinearLayout.LayoutParams(0, dp(38), 1));
        screenContainer.addView(modes, new LinearLayout.LayoutParams(-1, dp(46)));
        if (tracks.isEmpty()) addEmpty("QUEUE IS EMPTY", "Play a song or add tracks from your library.");
        else addTrackList(tracks, false, "__QUEUE__");
    }

    private void renderNowPlayingScreen() {
        pageTitle.setText("NOW PLAYING");
        pageAction.setVisibility(View.GONE);
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, dp(22), 0, dp(20));
        TextView eyebrow = label("NOW PLAYING", 10, color(R.color.redline), Typeface.BOLD);
        eyebrow.setLetterSpacing(.2f);
        view.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(26)));

        FrameLayout artFrame = new FrameLayout(this);
        artFrame.setBackground(round(color(R.color.panel), dp(18)));
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artFrame.addView(nowArt, new FrameLayout.LayoutParams(-1, -1));
        nowArtFallback = label("R", 110, color(R.color.redline), Typeface.BOLD);
        nowArtFallback.setGravity(Gravity.CENTER);
        artFrame.addView(nowArtFallback, new FrameLayout.LayoutParams(-1, -1));
        view.addView(artFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        nowTitle = label(stateTitle.isEmpty() ? "NOTHING PLAYING" : stateTitle, 25, Color.WHITE, Typeface.BOLD);
        nowTitle.setGravity(Gravity.CENTER);
        nowTitle.setMaxLines(2);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        view.addView(nowTitle, new LinearLayout.LayoutParams(-1, dp(64)));
        nowArtist = label(stateArtist.isEmpty() ? "Choose a song from your library" : stateArtist, 13, color(R.color.muted), Typeface.NORMAL);
        nowArtist.setGravity(Gravity.CENTER);
        view.addView(nowArtist, new LinearLayout.LayoutParams(-1, dp(34)));

        nowSeek = new SeekBar(this);
        nowSeek.setMax((int) Math.max(1L, stateDuration));
        nowSeek.setProgress((int) Math.min(statePosition, stateDuration));
        nowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { if (fromUser) sendSeek(value); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        view.addView(nowSeek, new LinearLayout.LayoutParams(-1, dp(32)));
        LinearLayout times = new LinearLayout(this);
        nowElapsed = label(formatTime(statePosition), 11, color(R.color.muted), Typeface.NORMAL);
        nowDuration = label(formatTime(stateDuration), 11, color(R.color.muted), Typeface.NORMAL);
        nowDuration.setGravity(Gravity.RIGHT);
        times.addView(nowElapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(nowDuration, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(times, new LinearLayout.LayoutParams(-1, dp(24)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        TextView previous = roundControl("PREV", "Previous");
        TextView back = roundControl("-10", "Back 10 seconds");
        nowPlay = roundControl(statePlaying ? "PAUSE" : "PLAY", "Play or pause");
        TextView forward = roundControl("+30", "Forward 30 seconds");
        TextView next = roundControl("NEXT", "Next");
        previous.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_PREVIOUS));
        back.setOnClickListener(v -> sendSeek(Math.max(0L, statePosition - 10000L)));
        nowPlay.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_TOGGLE));
        forward.setOnClickListener(v -> sendSeek(Math.min(stateDuration, statePosition + 30000L)));
        next.setOnClickListener(v -> PlayerService.send(this, PlayerService.ACTION_NEXT));
        controls.addView(previous, controlParams(dp(54)));
        controls.addView(back, controlParams(dp(54)));
        controls.addView(nowPlay, controlParams(dp(82)));
        controls.addView(forward, controlParams(dp(54)));
        controls.addView(next, controlParams(dp(54)));
        view.addView(controls, new LinearLayout.LayoutParams(-1, dp(82)));
        TextView mode = actionText((stateShuffle ? "SHUFFLE ON" : "SHUFFLE OFF") + "    /    REPEAT " + repeatName(), () -> {
            sendValue(PlayerService.ACTION_SHUFFLE, !stateShuffle);
        });
        mode.setGravity(Gravity.CENTER);
        view.addView(mode, new LinearLayout.LayoutParams(-1, dp(34)));
        screenContainer.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        loadArtwork();
        updateNowPlaying();
    }

    private void loadArtwork() {
        if (nowArt == null || stateUri == null) return;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, Uri.parse(stateUri));
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes != null) {
                Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                nowArt.setImageBitmap(bitmap);
                nowArtFallback.setVisibility(View.GONE);
            } else {
                nowArt.setImageDrawable(null);
                nowArtFallback.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {
            nowArtFallback.setVisibility(View.VISIBLE);
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private void addGroupList(ArrayList<String> keys) {
        if (keys.isEmpty()) { addEmpty("NOTHING INDEXED", "Use + ADD to import local music."); return; }
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new GroupAdapter(keys, libraryTab));
        list.setOnItemClickListener((parent, view, position, id) -> { groupFilter = keys.get(position); renderScreen(); });
        screenContainer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void addTrackList(ArrayList<Track> tracks, boolean allowPlaylistActions, String contextName) {
        if (tracks.isEmpty()) { addEmpty("NO SONGS HERE", "Import local audio or add songs to this playlist."); return; }
        ListView list = new ListView(this);
        list.setDivider(null);
        TrackAdapter adapter = new TrackAdapter(tracks, allowPlaylistActions, contextName);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ArrayList<Track> current = new ArrayList<>();
            for (int i = 0; i < adapter.getCount(); i++) current.add(adapter.getItem(i));
            playTracks(current, position);
        });
        screenContainer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void addEmpty(String title, String message) {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        TextView headline = label(title, 16, Color.WHITE, Typeface.BOLD);
        headline.setGravity(Gravity.CENTER);
        TextView detail = label(message, 12, color(R.color.muted), Typeface.NORMAL);
        detail.setGravity(Gravity.CENTER);
        empty.addView(headline, new LinearLayout.LayoutParams(-1, dp(32)));
        empty.addView(detail, new LinearLayout.LayoutParams(-1, dp(42)));
        screenContainer.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout heading(String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView headline = label(title, 20, Color.WHITE, Typeface.BOLD);
        row.addView(headline, new LinearLayout.LayoutParams(0, -1, 1));
        TextView count = label(detail, 11, color(R.color.muted), Typeface.NORMAL);
        row.addView(count);
        return row;
    }

    private ArrayList<Track> filteredTracks() {
        ArrayList<Track> result = new ArrayList<>();
        for (Track track : LibraryStore.getLibrary(this)) if (groupFilter == null || groupValue(track, libraryTab).equals(groupFilter)) result.add(track);
        return result;
    }

    private ArrayList<String> groupedKeys(int tab) {
        LinkedHashMap<String, Integer> groups = new LinkedHashMap<>();
        ArrayList<Track> tracks = LibraryStore.getLibrary(this);
        Collections.sort(tracks, Comparator.comparing(track -> groupValue(track, tab).toLowerCase()));
        for (Track track : tracks) {
            String key = groupValue(track, tab);
            groups.put(key, groups.containsKey(key) ? groups.get(key) + 1 : 1);
        }
        ArrayList<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : groups.entrySet()) result.add(entry.getKey() + "\u0000" + entry.getValue());
        return result;
    }

    private String groupValue(Track track, int tab) {
        if (tab == 1) return track.album;
        if (tab == 2) return track.artist;
        return track.folder;
    }

    private String tabName() {
        return new String[]{"SONGS", "ALBUMS", "ARTISTS", "FOLDERS"}[libraryTab];
    }

    private void playTracks(ArrayList<Track> tracks, int position) {
        if (tracks.isEmpty()) return;
        Intent intent = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_SET_QUEUE);
        intent.putStringArrayListExtra(PlayerService.EXTRA_URIS, LibraryStore.trackUris(tracks));
        intent.putExtra(PlayerService.EXTRA_INDEX, position);
        intent.putExtra(PlayerService.EXTRA_PLAY, true);
        startServiceCommand(intent);
        stateUri = tracks.get(position).uri;
        screen = SCREEN_NOW_PLAYING;
        renderScreen();
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
        }
        int added = LibraryStore.importUris(this, uris);
        refreshLibrary();
        Toast.makeText(this, added + (added == 1 ? " song added" : " songs added"), Toast.LENGTH_SHORT).show();
        if ((stateUri == null || stateUri.isEmpty()) && added > 0) {
            ArrayList<Track> tracks = LibraryStore.getLibrary(this);
            int index = 0;
            for (int i = 0; i < tracks.size(); i++) {
                for (Uri importedUri : uris) if (tracks.get(i).uri.equals(importedUri.toString())) { index = i; break; }
            }
            playTracks(tracks, index);
        }
    }

    private void refreshLibrary() {
        LibraryStore.validateLibrary(this);
        renderScreen();
    }

    private void restoreDisplayedState() {
        stateUri = LibraryStore.getLastUri(this);
        Track track = LibraryStore.find(this, stateUri);
        if (track != null) {
            stateTitle = track.title;
            stateArtist = track.artist;
            stateAlbum = track.album;
            statePosition = LibraryStore.getLastPosition(this);
            stateDuration = track.duration;
        }
        stateShuffle = LibraryStore.isShuffle(this);
        stateRepeat = LibraryStore.getRepeat(this);
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    private void updateMiniPlayer() {
        if (miniTitle == null) return;
        miniTitle.setText(stateTitle == null || stateTitle.isEmpty() ? "NOTHING PLAYING" : stateTitle);
        miniMeta.setText(stateArtist == null || stateArtist.isEmpty() ? "Choose a song to start" : stateArtist + "  /  " + formatTime(statePosition));
        miniPlay.setText(statePlaying ? "PAUSE" : "PLAY");
    }

    private void updateNowPlaying() {
        if (nowTitle == null) return;
        nowTitle.setText(stateTitle == null || stateTitle.isEmpty() ? "NOTHING PLAYING" : stateTitle);
        nowArtist.setText(stateArtist == null || stateArtist.isEmpty() ? "Choose a song from your library" : stateArtist + "  /  " + stateAlbum);
        nowElapsed.setText(formatTime(statePosition));
        nowDuration.setText(formatTime(stateDuration));
        nowPlay.setText(statePlaying ? "PAUSE" : "PLAY");
        nowSeek.setMax((int) Math.max(1L, stateDuration));
        nowSeek.setProgress((int) Math.min(statePosition, stateDuration));
    }

    private void updateNavigationColors() {
        if (navigation == null) return;
        for (int i = 0; i < navigation.getChildCount(); i++) navigation.getChildAt(i).setAlpha(i == screen ? 1f : .55f);
    }

    private void showTrackMenu(Track track, int position, String contextName) {
        PopupMenu menu = new PopupMenu(this, screenContainer);
        menu.getMenu().add("Play next");
        menu.getMenu().add("Add to playlist");
        if ("__QUEUE__".equals(contextName)) {
            menu.getMenu().add("Move up");
            menu.getMenu().add("Move down");
            menu.getMenu().add("Remove from queue");
        } else if (currentPlaylist != null && currentPlaylist.equals(contextName)) {
            menu.getMenu().add("Remove from playlist");
        }
        menu.getMenu().add("Remove from library");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if (action.equals("Play next")) playNext(track);
            else if (action.equals("Add to playlist")) showPlaylistPicker(track);
            else if (action.equals("Move up")) moveQueue(position, -1);
            else if (action.equals("Move down")) moveQueue(position, 1);
            else if (action.equals("Remove from queue")) sendIndex(PlayerService.ACTION_REMOVE_QUEUE, position);
            else if (action.equals("Remove from playlist")) { LibraryStore.removeFromPlaylist(this, currentPlaylist, track.uri); renderScreen(); }
            else if (action.equals("Remove from library")) removeFromLibrary(track);
            return true;
        });
        menu.show();
    }

    private void playNext(Track track) {
        ArrayList<Track> queue = LibraryStore.tracksForUris(this, LibraryStore.getQueueUris(this));
        int current = findTrack(queue, stateUri);
        queue.remove(track);
        int insertAt = current < 0 ? 0 : Math.min(current + 1, queue.size());
        queue.add(insertAt, track);
        sendQueue(queue, Math.max(0, findTrack(queue, stateUri)), statePlaying);
    }

    private void moveQueue(int position, int delta) {
        ArrayList<Track> queue = LibraryStore.tracksForUris(this, LibraryStore.getQueueUris(this));
        int target = position + delta;
        if (position < 0 || target < 0 || target >= queue.size()) return;
        Collections.swap(queue, position, target);
        sendQueue(queue, Math.max(0, findTrack(queue, stateUri)), statePlaying);
        renderScreen();
    }

    private void sendQueue(ArrayList<Track> queue, int index, boolean play) {
        Intent intent = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_SET_QUEUE);
        intent.putStringArrayListExtra(PlayerService.EXTRA_URIS, LibraryStore.trackUris(queue));
        intent.putExtra(PlayerService.EXTRA_INDEX, index);
        intent.putExtra(PlayerService.EXTRA_PLAY, play);
        startServiceCommand(intent);
    }

    private void removeFromLibrary(Track track) {
        ArrayList<Track> queued = LibraryStore.tracksForUris(this, LibraryStore.getQueueUris(this));
        int index = findTrack(queued, track.uri);
        LibraryStore.removeUri(this, track.uri);
        if (index >= 0) sendIndex(PlayerService.ACTION_REMOVE_QUEUE, index);
        refreshLibrary();
    }

    private void showPlaylistPicker(Track track) {
        ArrayList<String> names = LibraryStore.getPlaylistNames(this);
        if (names.isEmpty()) { showCreatePlaylistDialog(); return; }
        names.add("+ NEW PLAYLIST");
        new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names.toArray(new String[0]), (dialog, which) -> {
            String selected = names.get(which);
            if (selected.equals("+ NEW PLAYLIST")) showCreatePlaylistDialog(); else { LibraryStore.addToPlaylist(this, selected, track.uri); Toast.makeText(this, "Added to " + selected, Toast.LENGTH_SHORT).show(); }
        }).show();
    }

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = dp(22);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(-1, dp(54)));
        new AlertDialog.Builder(this).setTitle("New playlist").setView(wrap).setNegativeButton("CANCEL", null).setPositiveButton("CREATE", (dialog, which) -> {
            LibraryStore.createPlaylist(this, input.getText().toString());
            renderScreen();
        }).show();
    }

    private void showPlaylistMenu(String name) {
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Open", "Delete playlist"}, (dialog, which) -> {
            if (which == 0) { currentPlaylist = name; renderScreen(); }
            else new AlertDialog.Builder(this).setTitle("Delete playlist?").setMessage("The songs stay in your library.").setNegativeButton("CANCEL", null).setPositiveButton("DELETE", (d, w) -> { LibraryStore.deletePlaylist(this, name); renderScreen(); }).show();
        }).show();
    }

    private void sendSeek(long position) {
        Intent intent = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_SEEK).putExtra(PlayerService.EXTRA_POSITION, position);
        startServiceCommand(intent);
    }

    private void sendValue(String action, boolean value) {
        Intent intent = new Intent(this, PlayerService.class).setAction(action).putExtra(PlayerService.EXTRA_VALUE, value);
        startServiceCommand(intent);
    }

    private void sendInt(String action, int value) {
        Intent intent = new Intent(this, PlayerService.class).setAction(action).putExtra(PlayerService.EXTRA_VALUE, value);
        startServiceCommand(intent);
    }

    private void sendIndex(String action, int index) {
        Intent intent = new Intent(this, PlayerService.class).setAction(action).putExtra(PlayerService.EXTRA_INDEX, index);
        startServiceCommand(intent);
    }

    private void startServiceCommand(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    @Override public void onBackPressed() {
        if (screen == SCREEN_NOW_PLAYING) { screen = SCREEN_LIBRARY; renderScreen(); }
        else if (currentPlaylist != null) { currentPlaylist = null; renderScreen(); }
        else if (groupFilter != null) { groupFilter = null; renderScreen(); }
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private final class TrackAdapter extends BaseAdapter {
        private final ArrayList<Track> tracks;
        private final boolean playlistActions;
        private final String contextName;

        TrackAdapter(ArrayList<Track> tracks, boolean playlistActions, String contextName) {
            this.tracks = tracks;
            this.playlistActions = playlistActions;
            this.contextName = contextName;
        }

        @Override public int getCount() { return tracks.size(); }
        @Override public Track getItem(int position) { return tracks.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Track track = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(4), dp(6));
            row.setBackground(round(color(R.color.panel), dp(10)));
            TextView art = label(initial(track.title), 20, color(R.color.redline), Typeface.BOLD);
            art.setGravity(Gravity.CENTER);
            art.setBackground(round(color(R.color.ink), dp(8)));
            row.addView(art, new LinearLayout.LayoutParams(dp(48), dp(52)));
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12), 0, dp(4), 0);
            TextView title = label(track.title, 14, Color.WHITE, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView meta = label(track.artist + "  /  " + formatTime(track.duration), 11, color(R.color.muted), Typeface.NORMAL);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
            text.addView(meta, new LinearLayout.LayoutParams(-1, 0, 1));
            row.addView(text, new LinearLayout.LayoutParams(0, dp(52), 1));
            TextView more = label("...", 18, color(R.color.muted), Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            more.setContentDescription("More options for " + track.title);
            more.setOnClickListener(v -> showTrackMenu(track, position, contextName));
            row.addView(more, new LinearLayout.LayoutParams(dp(40), dp(52)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(64));
            rowParams.setMargins(0, 0, 0, dp(5));
            row.setLayoutParams(rowParams);
            return row;
        }
    }

    private final class GroupAdapter extends BaseAdapter {
        private final ArrayList<String> groups;
        private final int type;
        GroupAdapter(ArrayList<String> groups, int type) { this.groups = groups; this.type = type; }
        @Override public int getCount() { return groups.size(); }
        @Override public String getItem(int position) { return groups.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            String[] parts = getItem(position).split("\\u0000", 2);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(8));
            row.setBackground(round(color(R.color.panel), dp(10)));
            TextView icon = label(type == 1 ? "A" : type == 2 ? "@" : "F", 20, color(R.color.redline), Typeface.BOLD);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12), 0, 0, 0);
            text.addView(label(parts[0], 15, Color.WHITE, Typeface.BOLD), new LinearLayout.LayoutParams(-1, 0, 1));
            text.addView(label(parts.length > 1 ? parts[1] + " songs" : "", 11, color(R.color.muted), Typeface.NORMAL), new LinearLayout.LayoutParams(-1, 0, 1));
            row.addView(text, new LinearLayout.LayoutParams(0, dp(56), 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(68));
            params.setMargins(0, 0, 0, dp(5));
            row.setLayoutParams(params);
            return row;
        }
    }

    private final class PlaylistAdapter extends BaseAdapter {
        private final ArrayList<String> names;
        PlaylistAdapter(ArrayList<String> names) { this.names = names; }
        @Override public int getCount() { return names.size(); }
        @Override public String getItem(int position) { return names.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(8));
            row.setBackground(round(color(R.color.panel), dp(10)));
            TextView icon = label("P", 20, color(R.color.redline), Typeface.BOLD);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView title = label(getItem(position), 16, Color.WHITE, Typeface.BOLD);
            title.setPadding(dp(12), 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
            TextView more = label("...", 18, color(R.color.muted), Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            more.setOnClickListener(v -> showPlaylistMenu(getItem(position)));
            row.addView(more, new LinearLayout.LayoutParams(dp(40), dp(56)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(68));
            params.setMargins(0, 0, 0, dp(5));
            row.setLayoutParams(params);
            return row;
        }
    }

    private TextView modeButton(String text) {
        TextView view = label(text, 10, Color.WHITE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(color(R.color.panel_light), dp(6)));
        return view;
    }

    private TextView roundControl(String text, String description) {
        TextView view = label(text, text.equals("PLAY") || text.equals("PAUSE") ? 12 : 10, Color.WHITE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(round(text.equals("PLAY") || text.equals("PAUSE") ? color(R.color.redline) : color(R.color.panel_light), dp(100)));
        return view;
    }

    private TextView actionText(String text, Runnable action) {
        TextView view = label(text, 11, color(R.color.redline), Typeface.BOLD);
        view.setLetterSpacing(.08f);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private LinearLayout.LayoutParams controlParams(int size) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int findTrack(List<Track> tracks, String uri) {
        if (uri == null) return -1;
        for (int i = 0; i < tracks.size(); i++) if (uri.equals(tracks.get(i).uri)) return i;
        return -1;
    }

    private String repeatName() {
        return stateRepeat == 1 ? "ALL" : stateRepeat == 2 ? "ONE" : "OFF";
    }

    private String initial(String text) { return text == null || text.isEmpty() ? "R" : text.substring(0, 1).toUpperCase(); }

    private TextView label(String text, float size, int textColor, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int color(int id) { return getResources().getColor(id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }
    private String safe(String value) { return value == null ? "" : value; }

    private String formatTime(long millis) {
        int seconds = (int) Math.max(0L, millis / 1000L);
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
