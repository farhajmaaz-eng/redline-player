package com.redline.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlayerService extends Service {
    public static final String ACTION_STATE = "com.redline.player.STATE";
    public static final String ACTION_SET_QUEUE = "com.redline.player.SET_QUEUE";
    public static final String ACTION_TOGGLE = "com.redline.player.TOGGLE";
    public static final String ACTION_NEXT = "com.redline.player.NEXT";
    public static final String ACTION_PREVIOUS = "com.redline.player.PREVIOUS";
    public static final String ACTION_SEEK = "com.redline.player.SEEK";
    public static final String ACTION_SHUFFLE = "com.redline.player.SHUFFLE";
    public static final String ACTION_REPEAT = "com.redline.player.REPEAT";
    public static final String ACTION_REMOVE_QUEUE = "com.redline.player.REMOVE_QUEUE";
    public static final String ACTION_CLEAR_QUEUE = "com.redline.player.CLEAR_QUEUE";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_PLAY = "play";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_URI = "uri";

    private static final String CHANNEL_ID = "redline_playback";
    private static final int NOTIFICATION_ID = 9;

    private final Handler handler = new Handler();
    private final Random random = new Random();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            saveState();
            broadcastState();
            handler.postDelayed(this, 1000L);
        }
    };

    private ArrayList<Track> queue = new ArrayList<>();
    private int currentIndex = -1;
    private MediaPlayer player;
    private Track currentTrack;
    private boolean prepared;
    private boolean resumeOnFocusGain;
    private boolean shuffle;
    private int repeat;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession mediaSession;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain = isPlaying();
            pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(.25f, .25f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                startPlayback();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        shuffle = LibraryStore.isShuffle(this);
        repeat = LibraryStore.getRepeat(this);
        createNotificationChannel();
        setupMediaSession();
        startForeground(NOTIFICATION_ID, buildNotification());
        restoreState();
        handler.post(ticker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) handle(intent);
        return START_STICKY;
    }

    private void handle(Intent intent) {
        String action = intent.getAction();
        if (ACTION_SET_QUEUE.equals(action)) {
            ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_URIS);
            if (uris == null) uris = new ArrayList<>();
            ArrayList<Track> nextQueue = LibraryStore.tracksForUris(this, uris);
            int index = intent.getIntExtra(EXTRA_INDEX, 0);
            setQueue(nextQueue, index, intent.getBooleanExtra(EXTRA_PLAY, false));
        } else if (ACTION_TOGGLE.equals(action)) {
            if (isPlaying()) pausePlayback(); else startPlayback();
        } else if (ACTION_NEXT.equals(action)) {
            advance(true);
        } else if (ACTION_PREVIOUS.equals(action)) {
            previous();
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getLongExtra(EXTRA_POSITION, 0L));
        } else if (ACTION_SHUFFLE.equals(action)) {
            shuffle = intent.getBooleanExtra(EXTRA_VALUE, !shuffle);
            saveState();
            broadcastState();
        } else if (ACTION_REPEAT.equals(action)) {
            repeat = intent.getIntExtra(EXTRA_VALUE, (repeat + 1) % 3);
            saveState();
            broadcastState();
        } else if (ACTION_REMOVE_QUEUE.equals(action)) {
            removeFromQueue(intent.getIntExtra(EXTRA_INDEX, -1));
        } else if (ACTION_CLEAR_QUEUE.equals(action)) {
            queue.clear();
            currentIndex = -1;
            currentTrack = null;
            releasePlayer();
            saveState();
            broadcastState();
        }
        updateNotification();
    }

    private void restoreState() {
        queue = LibraryStore.tracksForUris(this, LibraryStore.getQueueUris(this));
        String lastUri = LibraryStore.getLastUri(this);
        currentIndex = findUri(lastUri);
        if (currentIndex >= 0) {
            currentTrack = queue.get(currentIndex);
            loadCurrent(false, LibraryStore.getLastPosition(this));
        } else {
            broadcastState();
        }
    }

    private void setQueue(ArrayList<Track> nextQueue, int index, boolean play) {
        if (nextQueue.isEmpty()) {
            queue.clear();
            currentIndex = -1;
            currentTrack = null;
            releasePlayer();
            saveState();
            broadcastState();
            return;
        }
        String previousUri = currentTrack == null ? null : currentTrack.uri;
        queue = nextQueue;
        currentIndex = Math.max(0, Math.min(index, queue.size() - 1));
        Track requested = queue.get(currentIndex);
        if (!requested.uri.equals(previousUri) || player == null || !prepared) {
            loadCurrent(play, 0L);
        } else if (play) {
            startPlayback();
        } else {
            saveState();
            broadcastState();
        }
    }

    private void loadCurrent(boolean playWhenReady, long resumePosition) {
        releasePlayer();
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            broadcastState();
            return;
        }
        currentTrack = queue.get(currentIndex);
        player = new MediaPlayer();
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            if (resumePosition > 0L) mp.seekTo((int) Math.min(resumePosition, mp.getDuration()));
            if (playWhenReady) startPlayback();
            else broadcastState();
            updateNotification();
        });
        player.setOnCompletionListener(mp -> advance(false));
        player.setOnErrorListener((mp, what, extra) -> {
            if (currentTrack != null) {
                LibraryStore.removeUri(this, currentTrack.uri);
                queue.remove(currentIndex);
                if (currentIndex >= queue.size()) currentIndex = queue.size() - 1;
            }
            if (queue.isEmpty()) {
                releasePlayer();
                broadcastState();
            } else {
                loadCurrent(true, 0L);
            }
            return true;
        });
        try {
            player.setDataSource(this, Uri.parse(currentTrack.uri));
            player.prepareAsync();
            saveState();
            broadcastState();
        } catch (Exception error) {
            LibraryStore.removeUri(this, currentTrack.uri);
            queue.remove(currentIndex);
            if (currentIndex >= queue.size()) currentIndex = queue.size() - 1;
            if (queue.isEmpty()) releasePlayer(); else loadCurrent(true, 0L);
        }
    }

    private void advance(boolean fromCommand) {
        if (queue.isEmpty()) return;
        if (repeat == 2 && !fromCommand) {
            seekTo(0L);
            startPlayback();
            return;
        }
        int nextIndex;
        if (shuffle && queue.size() > 1) {
            nextIndex = currentIndex;
            while (nextIndex == currentIndex) nextIndex = random.nextInt(queue.size());
        } else {
            nextIndex = currentIndex + 1;
            if (nextIndex >= queue.size()) {
                if (repeat == 1) nextIndex = 0;
                else {
                    seekTo(0L);
                    pausePlayback();
                    return;
                }
            }
        }
        currentIndex = nextIndex;
        loadCurrent(true, 0L);
    }

    private void previous() {
        if (queue.isEmpty()) return;
        if (getPosition() > 5000L) {
            seekTo(0L);
            return;
        }
        currentIndex = currentIndex <= 0 ? queue.size() - 1 : currentIndex - 1;
        loadCurrent(true, 0L);
    }

    private void removeFromQueue(int index) {
        if (index < 0 || index >= queue.size()) return;
        boolean removingCurrent = index == currentIndex;
        queue.remove(index);
        if (index < currentIndex) currentIndex--;
        if (queue.isEmpty()) {
            currentIndex = -1;
            releasePlayer();
        } else if (removingCurrent) {
            if (currentIndex >= queue.size()) currentIndex = 0;
            loadCurrent(isPlaying(), 0L);
        }
        saveState();
        broadcastState();
    }

    private void startPlayback() {
        if (!prepared || player == null) return;
        if (!requestAudioFocus()) return;
        player.start();
        updateMediaSession();
        broadcastState();
    }

    private void pausePlayback() {
        if (player != null && prepared && player.isPlaying()) player.pause();
        saveState();
        updateMediaSession();
        broadcastState();
    }

    private void seekTo(long position) {
        if (player != null && prepared) player.seekTo((int) Math.max(0L, Math.min(position, player.getDuration())));
        else if (currentTrack != null) LibraryStore.savePlayback(this, currentTrack.uri, position, shuffle, repeat);
        broadcastState();
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build();
            }
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focusListener);
    }

    private boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    private long getPosition() {
        return player != null && prepared ? player.getCurrentPosition() : LibraryStore.getLastPosition(this);
    }

    private int findUri(String uri) {
        if (uri == null) return -1;
        for (int i = 0; i < queue.size(); i++) if (uri.equals(queue.get(i).uri)) return i;
        return -1;
    }

    private void saveState() {
        LibraryStore.saveQueue(this, queue);
        if (currentTrack != null) LibraryStore.savePlayback(this, currentTrack.uri, getPosition(), shuffle, repeat);
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "RedlinePlayer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { startPlayback(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onSkipToNext() { advance(true); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state, getPosition(), 1f).build());
        if (currentTrack != null) {
            Bundle extras = new Bundle();
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentTrack.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, currentTrack.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, currentTrack.duration)
                    .build());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, pendingFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTrack == null ? "Redline Player" : currentTrack.title)
                .setContentText(currentTrack == null ? "Ready for offline music" : currentTrack.artist)
                .setContentIntent(content)
                .setOngoing(isPlaying())
                .setShowWhen(false)
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return builder
                .addAction(android.R.drawable.ic_media_previous, "Previous", servicePending(ACTION_PREVIOUS, 2))
                .addAction(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, isPlaying() ? "Pause" : "Play", servicePending(ACTION_TOGGLE, 3))
                .addAction(android.R.drawable.ic_media_next, "Next", servicePending(ACTION_NEXT, 4))
                .build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode, new Intent(this, PlayerService.class).setAction(action), pendingFlags());
    }

    private int pendingFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void updateNotification() {
        updateMediaSession();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Redline music controls");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void broadcastState() {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_URI, currentTrack == null ? null : currentTrack.uri);
        state.putExtra("title", currentTrack == null ? "" : currentTrack.title);
        state.putExtra("artist", currentTrack == null ? "" : currentTrack.artist);
        state.putExtra("album", currentTrack == null ? "" : currentTrack.album);
        state.putExtra("position", getPosition());
        state.putExtra("duration", player != null && prepared ? (long) player.getDuration() : currentTrack == null ? 0L : currentTrack.duration);
        state.putExtra("playing", isPlaying());
        state.putExtra("queue_size", queue.size());
        state.putExtra("index", currentIndex);
        state.putExtra("shuffle", shuffle);
        state.putExtra("repeat", repeat);
        sendBroadcast(state);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        saveState();
        abandonAudioFocus();
        releasePlayer();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

    public static void ensureStarted(Context context) {
        Intent intent = new Intent(context, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    public static void send(Context context, String action) {
        Intent intent = new Intent(context, PlayerService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
}
