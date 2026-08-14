package com.nova.launcher;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_TIMEOUT = 8000; // 8 seconds timeout to match video
    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Make full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_splash);

        videoView = findViewById(R.id.splash_video);
        
        // Try to play the video
        try {
            String videoPath = "android_asset://videos/os.mp4";
            videoView.setVideoURI(Uri.parse(videoPath));
            videoView.setOnCompletionListener(mp -> {
                // Video completed, start main activity
                startMainActivity();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                // Error playing video, start main activity after timeout
                new Handler(Looper.getMainLooper()).postDelayed(this::startMainActivity, 1000);
                return true;
            });
            videoView.start();
        } catch (Exception e) {
            // Exception, start main activity after timeout
            new Handler(Looper.getMainLooper()).postDelayed(this::startMainActivity, 1000);
        }
        
        // Fallback timeout in case video doesn't load properly
        new Handler(Looper.getMainLooper()).postDelayed(this::startMainActivity, SPLASH_TIMEOUT);
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish(); // Close SplashActivity
    }
}
