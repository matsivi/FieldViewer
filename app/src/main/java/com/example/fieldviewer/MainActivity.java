package com.example.fieldviewer;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Main entry point activity for the FieldViewer app
 * Features:
 * - Looping background video for visual appeal
 * - GPS warmup countdown before allowing measurements
 * - Navigation to AR measurement and saved measurements
 */
public class MainActivity extends AppCompatActivity {

    // Video playback components for background loop
    private TextureView textureView;
    private MediaPlayer mediaPlayer;
    private android.os.CountDownTimer warmupTimer;
    private boolean warmupDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        // Handle system UI insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize video texture view for background loop
        textureView = findViewById(R.id.textureVideo);
        if (textureView != null) {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    startBackgroundVideo(new Surface(surface));
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    stopBackgroundVideo();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            });
        }

        // Set up button click listener for starting measurements
        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!warmupDone) {
                    android.widget.Toast.makeText(MainActivity.this, "Please wait for GPS warmup to finish", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(MainActivity.this, ARMeasureActivity.class);
                startActivity(intent);
            }
        });

        // Set up button for viewing saved measurements
        Button btnSaved = findViewById(R.id.btnSaved);
        if (btnSaved != null) {
            btnSaved.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, SavedMeasurementsActivity.class);
                    startActivity(i);
                }
            });
        }

        // Show popup guidance and start a 30s GPS warmup countdown
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("GPS Warmup")
                .setMessage("Please stay still for about 30 seconds so the device can get an accurate location fix before measuring.")
                .setPositiveButton("OK", (d, w) -> startWarmupCountdown())
                .setCancelable(false)
                .show();
    }

    /**
     * Starts a 30-second GPS warmup countdown
     * Ensures accurate location data before allowing measurements
     * Updates UI to show countdown progress and enables start button when complete
     */
    private void startWarmupCountdown() {
        final TextView tvWarmup = findViewById(R.id.tvWarmup);
        final Button btnStart = findViewById(R.id.btnStart);
        if (btnStart != null) btnStart.setEnabled(false);
        warmupDone = false;
        if (warmupTimer != null) {
            try { warmupTimer.cancel(); } catch (Exception ignored) {}
            warmupTimer = null;
        }
        warmupTimer = new android.os.CountDownTimer(30000, 1000) {
            @Override public void onTick(long ms) {
                int s = (int) Math.ceil(ms / 1000.0);
                if (tvWarmup != null) tvWarmup.setText("Preparing GPS: " + s + "s");
            }
            @Override public void onFinish() {
                warmupDone = true;
                warmupTimer = null;
                if (tvWarmup != null) tvWarmup.setText("GPS Ready");
                if (btnStart != null) btnStart.setEnabled(true);
            }
        };
        warmupTimer.start();
    }

    /**
     * Starts the background video loop on the provided surface
     * Attempts to load from assets first, falls back to raw resources
     * Sets video to loop silently with low opacity for background effect
     */
    private void startBackgroundVideo(Surface surface) {
        stopBackgroundVideo();
        try {
            mediaPlayer = new MediaPlayer();
            android.content.res.AssetFileDescriptor afd = null;
            try {
                // Try to load video from assets folder first
                afd = getAssets().openFd("background_loop.mp4");
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } catch (Exception ex) {
                // Fallback: try raw resource if asset missing
                try {
                    android.net.Uri uri = android.net.Uri.parse("android.resource://" + getPackageName() + "/raw/background_loop");
                    mediaPlayer.setDataSource(this, uri);
                } catch (Exception ignored) {}
            } finally {
                if (afd != null) try { afd.close(); } catch (Exception ignore) {}
            }
            mediaPlayer.setSurface(surface);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);  // Silent playback
            mediaPlayer.setOnPreparedListener(mp -> mp.start());
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Stops and releases the background video player
     * Called when switching activities or destroying the view
     */
    private void stopBackgroundVideo() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {}
        mediaPlayer = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause video when app goes to background
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume video when returning to app
        if (mediaPlayer != null) {
            try { mediaPlayer.start(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBackgroundVideo();
        try { if (warmupTimer != null) { warmupTimer.cancel(); warmupTimer = null; } } catch (Exception ignored) {}
    }
}