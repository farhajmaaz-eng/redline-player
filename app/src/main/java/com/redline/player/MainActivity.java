package com.redline.player;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;

import android.app.Activity;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 41;

    private final Handler handler = new Handler();
    private MediaPlayer player;
    private boolean prepared;
    private Uri currentUri;
    private TextView trackTitle;
    private TextView trackMeta;
    private TextView elapsed;
    private TextView duration;
    private TextView playButton;
    private SeekBar seekBar;
    private ProgressBar loading;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (player != null && prepared) {
                seekBar.setProgress(player.getCurrentPosition());
                elapsed.setText(formatTime(player.getCurrentPosition()));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handler.post(progressTicker);

        String savedUri = getPreferences(MODE_PRIVATE).getString("last_uri", null);
        if (savedUri != null) {
            try {
                loadTrack(Uri.parse(savedUri), false);
            } catch (Exception ignored) {
                clearSavedTrack();
            }
        }
    }

    private void buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, dp(12));
        root.setBackgroundColor(Color.rgb(10, 10, 11));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("REDLINE", 22, Color.WHITE, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        TextView mark = label("MP3 PLAYER", 10, getColor(com.redline.player.R.color.redline), Typeface.BOLD);
        mark.setLetterSpacing(.16f);
        header.addView(mark);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));

        View rule = new View(this);
        rule.setBackgroundColor(getColor(com.redline.player.R.color.redline));
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(2)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, dp(32), 0, 0);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView eyebrow = label("NOW PLAYING", 11, getColor(com.redline.player.R.color.redline), Typeface.BOLD);
        eyebrow.setLetterSpacing(.22f);
        content.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));

        trackTitle = label("NO TRACK LOADED", 28, Color.WHITE, Typeface.BOLD);
        trackTitle.setGravity(Gravity.CENTER);
        trackTitle.setMaxLines(2);
        content.addView(trackTitle, new LinearLayout.LayoutParams(-1, dp(76)));

        trackMeta = label("Choose a song from your device to begin", 13, getColor(com.redline.player.R.color.muted), Typeface.NORMAL);
        trackMeta.setGravity(Gravity.CENTER);
        content.addView(trackMeta, new LinearLayout.LayoutParams(-1, dp(40)));

        FrameLayout artwork = new FrameLayout(this);
        artwork.setBackground(round(getColor(com.redline.player.R.color.panel), dp(18)));
        TextView artworkText = label("R", 104, getColor(com.redline.player.R.color.redline), Typeface.BOLD);
        artworkText.setGravity(Gravity.CENTER);
        artwork.addView(artworkText, new FrameLayout.LayoutParams(-1, -1));
        content.addView(artwork, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        timeline.setPadding(0, dp(24), 0, 0);
        seekBar = new SeekBar(this);
        seekBar.setMax(1);
        seekBar.setProgress(0);
        seekBar.setContentDescription("Track position");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && player != null && prepared) player.seekTo(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        timeline.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout times = new LinearLayout(this);
        elapsed = label("0:00", 12, getColor(com.redline.player.R.color.muted), Typeface.NORMAL);
        duration = label("0:00", 12, getColor(com.redline.player.R.color.muted), Typeface.NORMAL);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        duration.setGravity(Gravity.RIGHT);
        times.addView(duration, new LinearLayout.LayoutParams(0, -2, 1));
        timeline.addView(times);
        content.addView(timeline, new LinearLayout.LayoutParams(-1, dp(68)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        TextView back = control("10", "Back 10 seconds");
        TextView forward = control("30", "Forward 30 seconds");
        playButton = control("PLAY", "Play or pause");
        playButton.setTextSize(14);
        playButton.setTextColor(Color.WHITE);
        playButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playButton.setBackground(round(getColor(com.redline.player.R.color.redline), dp(100)));
        playButton.setOnClickListener(v -> togglePlayback());
        back.setOnClickListener(v -> skipBy(-10000));
        forward.setOnClickListener(v -> skipBy(30000));
        controls.addView(back, controlParams(dp(54)));
        controls.addView(playButton, controlParams(dp(92)));
        controls.addView(forward, controlParams(dp(54)));
        content.addView(controls, new LinearLayout.LayoutParams(-1, dp(92)));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = label("LOCAL FILES ONLY", 10, getColor(com.redline.player.R.color.muted), Typeface.BOLD);
        hint.setLetterSpacing(.1f);
        footer.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = new Button(this);
        choose.setText("+ ADD SONG");
        choose.setTextColor(Color.WHITE);
        choose.setTextSize(12);
        choose.setAllCaps(false);
        choose.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        choose.setPadding(dp(16), 0, dp(16), 0);
        choose.setBackground(round(getColor(com.redline.player.R.color.panel_light), dp(8)));
        choose.setOnClickListener(v -> openPicker());
        footer.addView(choose, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(58)));

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(4)));
        setContentView(root);
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        loadTrack(uri, true);
    }

    private void loadTrack(Uri uri, boolean autoplay) {
        releasePlayer();
        currentUri = uri;
        prepared = false;
        trackTitle.setText(queryDisplayName(uri));
        trackMeta.setText("Loading audio file...");
        playButton.setText("...");
        playButton.setEnabled(false);
        loading.setVisibility(View.VISIBLE);

        player = new MediaPlayer();
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            seekBar.setMax(mp.getDuration());
            duration.setText(formatTime(mp.getDuration()));
            trackMeta.setText("LOCAL AUDIO");
            playButton.setText("PLAY");
            playButton.setEnabled(true);
            loading.setVisibility(View.GONE);
            if (autoplay) startPlayback();
        });
        player.setOnCompletionListener(mp -> {
            seekBar.setProgress(0);
            elapsed.setText("0:00");
            playButton.setText("PLAY");
        });
        player.setOnErrorListener((mp, what, extra) -> {
            loading.setVisibility(View.GONE);
            playButton.setText("PLAY");
            playButton.setEnabled(true);
            trackMeta.setText("This audio file could not be played");
            return true;
        });
        try {
            player.setDataSource(this, uri);
            player.prepareAsync();
            getPreferences(MODE_PRIVATE).edit().putString("last_uri", uri.toString()).apply();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open this audio file", Toast.LENGTH_SHORT).show();
            releasePlayer();
            resetTrackUi();
        }
    }

    private void togglePlayback() {
        if (player == null || !prepared) {
            if (currentUri == null) openPicker();
            return;
        }
        if (player.isPlaying()) player.pause(); else startPlayback();
        playButton.setText(player.isPlaying() ? "PAUSE" : "PLAY");
    }

    private void startPlayback() {
        if (player != null && prepared) {
            player.start();
            playButton.setText("PAUSE");
        }
    }

    private void skipBy(int amount) {
        if (player == null || !prepared) return;
        int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + amount));
        player.seekTo(target);
        seekBar.setProgress(target);
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "SELECTED TRACK";
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
    }

    private void resetTrackUi() {
        currentUri = null;
        trackTitle.setText("NO TRACK LOADED");
        trackMeta.setText("Choose a song from your device to begin");
        elapsed.setText("0:00");
        duration.setText("0:00");
        seekBar.setMax(1);
        seekBar.setProgress(0);
        playButton.setText("PLAY");
        playButton.setEnabled(true);
    }

    private void clearSavedTrack() {
        getPreferences(MODE_PRIVATE).edit().remove("last_uri").apply();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressTicker);
        releasePlayer();
        super.onDestroy();
    }

    private TextView label(String text, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView control(String text, String description) {
        TextView view = label(text, 11, getColor(com.redline.player.R.color.muted), Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        return view;
    }

    private LinearLayout.LayoutParams controlParams(int size) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(8), 0, dp(8), 0);
        return params;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatTime(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
