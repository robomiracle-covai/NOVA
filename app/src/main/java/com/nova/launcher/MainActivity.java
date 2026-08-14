package com.nova.launcher;
import android.speech.RecognizerIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import android.content.res.AssetManager;
import android.content.SharedPreferences;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final int FILE_CHOOSER_RESULT_CODE = 101;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static MainActivity instance;
    private static boolean isAuthorized = false;

    private Button btnRetry;
    private boolean cameraPermissionGranted = false;
    private CameraServer cameraServer; // Assumes you have CameraServer.java in your project
    private LinearLayout errorLayout;
    private ValueCallback<Uri[]> fileUploadCallback;
    private boolean isTtsInitialized = false;
    private boolean micPermissionGranted = false;
    private int previousKeypadHeight = -1;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private WebView webView;

    // OTA Polling
    private final Handler otaHandler = new Handler(Looper.getMainLooper());
    private Runnable otaRunnable;
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final long OTA_POLL_INTERVAL_MS = 3_000; // 3 seconds for near-instant detection
    // Pending update info (set when update is available, cleared after install)
    private volatile String pendingUpdateUrl = null;
    private volatile String pendingUpdateVersion = null;
    private volatile String lastNotifiedVersion = null;
    
    // Pending APK update info
    private volatile String pendingApkUrl = null;
    private volatile String pendingApkVersion = null;
    private volatile String lastNotifiedApkVersion = null;
    private volatile boolean wasApkDownloadBlocked = false;

    private LocalAiEngine localAiEngine;

    private final AtomicBoolean isModelDownloading = new AtomicBoolean(false);

    public static boolean isAuthorized() {
        return isAuthorized;
    }

    public static void setAuthorized(boolean authorized) {
        isAuthorized = authorized;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main); // Make sure you have this layout

        this.webView = findViewById(R.id.web_view);

        initWebAssets();
        migrateOtaVersionKey();

        setupWebView();
        setupKeyboardWorkaround();
        checkPermissions();
        startCameraServer();
        hideSystemUI();
        handleIntent(getIntent());

        // Replaced synthetic lambda with standard lambda
        this.textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true;
                setMaleVoice();
                
                this.textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        runOnUiThread(() -> {
                            if (webView != null) webView.evaluateJavascript("if(window.onNativeTtsStart) window.onNativeTtsStart();", null);
                        });
                    }
                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            if (webView != null) webView.evaluateJavascript("if(window.onNativeTtsDone) window.onNativeTtsDone();", null);
                        });
                    }
                    @Override
                    public void onError(String utteranceId) {}
                });
            }
        });

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        this.localAiEngine = new LocalAiEngine(this);

        startOTAPolling();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("TRIGGER_LOCK", false) && this.webView != null) {
            File lockFile = new File(getFilesDir() + "/web_assets", "lock.html");
            this.webView.post(() -> this.webView.loadUrl("file://" + lockFile.getAbsolutePath()));
        }
    }

    private void initWebAssets() {
        File webAssetsDir = new File(getFilesDir(), "web_assets");
        boolean freshInstall = !webAssetsDir.exists();
        if (freshInstall) {
            webAssetsDir.mkdirs();
            copyAssetFolder(getAssets(), "", webAssetsDir.getAbsolutePath());
            Log.d("Launcher", "Copied initial assets to internal storage.");
        } else {
            // Always overwrite update.html so it is guaranteed to exist in internal storage
            copyAssetFile(getAssets(), "update.html", new File(webAssetsDir, "update.html").getAbsolutePath());
            // Ensure system_prompt.txt is updated from APK assets
            copyAssetFile(getAssets(), "system_prompt.txt", new File(webAssetsDir, "system_prompt.txt").getAbsolutePath());
            Log.d("Launcher", "Refreshed update.html and system_prompt.txt in internal storage.");
        }
    }

    private void copyAssetFolder(AssetManager assetManager, String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            if (files == null) return;
            if (files.length == 0) {
                copyAssetFile(assetManager, fromAssetPath, toPath);
            } else {
                File dir = new File(toPath);
                if (!dir.exists()) dir.mkdirs();
                for (String file : files) {
                    if (file.equals("images") || file.equals("webkit")) continue;
                    String nextFromPath = fromAssetPath.isEmpty() ? file : fromAssetPath + "/" + file;
                    String nextToPath = toPath + "/" + file;
                    copyAssetFolder(assetManager, nextFromPath, nextToPath);
                }
            }
        } catch (Exception e) {
            Log.e("Launcher", "Error copying asset folder", e);
        }
    }

    private void copyAssetFile(AssetManager assetManager, String fromAssetPath, String toPath) {
        try (InputStream in = assetManager.open(fromAssetPath);
             OutputStream out = new FileOutputStream(toPath)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.e("Launcher", "Error copying asset file: " + fromAssetPath, e);
        }
    }

    /**
     * Migrates the old integer ota_version key to float.
     * Also handles cases where the key was never set.
     * If the int key exists, copies its value to the float key and removes it.
     */
    private void migrateOtaVersionKey() {
        SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
        try {
            // Try to detect if an old int key exists by catching ClassCastException
            int oldVersion = prefs.getInt("ota_version", -1);
            if (oldVersion != -1) {
                // Old int key exists — migrate it
                prefs.edit()
                    .remove("ota_version")
                    .putFloat("ota_version_f", (float) oldVersion)
                    .apply();
                Log.d("Launcher", "Migrated ota_version int to float: " + oldVersion);
            }
        } catch (ClassCastException e) {
            // Already stored as float — no migration needed
            Log.d("Launcher", "ota_version is already float type.");
        }
    }

    /** Starts a repeating 10-second background OTA check loop. */
    private void startOTAPolling() {
        otaRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isUpdating.get()) {
                    checkForOTAUpdates(false);
                }
                otaHandler.postDelayed(this, OTA_POLL_INTERVAL_MS);
            }
        };
        // First check after 3 seconds (let app fully load), then every 10s
        otaHandler.postDelayed(otaRunnable, 3000);
        Log.d("Launcher", "OTA polling started — checking every " + (OTA_POLL_INTERVAL_MS/1000) + "s");
    }

    /** Stops the OTA polling loop (called on destroy). */
    private void stopOTAPolling() {
        if (otaRunnable != null) {
            otaHandler.removeCallbacks(otaRunnable);
            Log.d("Launcher", "OTA polling stopped.");
        }
    }

    public void checkForOTAUpdates() {
        checkForOTAUpdates(false);
    }

    public void checkForOTAUpdates(boolean manual) {
        // Guard: don't start a second download if one is already running
        if (isUpdating.get()) return;

        new Thread(() -> {
            try {
                // Add cache-busting timestamp so GitHub CDN never serves a stale response
                // Use raw.githubusercontent.com to bypass the 1-minute GitHub Pages build delay
                String checkUrl = "https://raw.githubusercontent.com/nadhilrobomiracle/nova_releases/main/update.json?t=" + System.currentTimeMillis();
                URL url = new URL(checkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                // Explicitly tell any CDN/proxy not to serve cached content
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Expires", "0");
                
                int responseCode = conn.getResponseCode();
                Log.d("Launcher", "OTA check HTTP response: " + responseCode + " from " + checkUrl);

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    Log.d("Launcher", "OTA JSON received: " + sb.toString());

                    JSONObject json = new JSONObject(sb.toString());
                    // Store version as String to avoid any float precision issues
                    String remoteVersionStr = json.optString("version", "0");
                    String downloadUrl = json.optString("download_url", "");

                    SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
                    String localVersionStr = prefs.getString("ota_version_str", "0");

                    Log.d("Launcher", "Version check — Remote: \"" + remoteVersionStr + "\" | Local: \"" + localVersionStr + "\"");

                    // Compare as doubles to handle "1.1" vs "1.2" correctly
                    double remoteVersion = Double.parseDouble(remoteVersionStr);
                    double localVersion = Double.parseDouble(localVersionStr);

                    if (remoteVersion > localVersion && !downloadUrl.isEmpty()) {
                        // Store the pending update so the JS bridge can retrieve it
                        pendingUpdateUrl = downloadUrl;
                        pendingUpdateVersion = remoteVersionStr;

                        // Only notify if we haven't notified for this version yet, or if it's a manual check
                        if (manual || !remoteVersionStr.equals(lastNotifiedVersion)) {
                            Log.d("Launcher", ">>> UPDATE AVAILABLE! Injecting Global UI...");
                            lastNotifiedVersion = remoteVersionStr;

                            String releaseNotes = json.optString("release_notes", "New version available").replace("'", "\\'");
                            
                            // Inject a global popup directly into the current WebView page (works on index.html, robot.html, etc)
                            String otaPopupHtml = "<div id='global-ota-popup' style='position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.85);z-index:9999;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(5px);font-family:sans-serif;'>" +
                                "<div style='background:rgba(4,15,30,0.95);border:1px solid rgba(0,243,255,0.3);border-radius:12px;padding:2rem;text-align:center;max-width:320px;box-shadow:0 0 30px rgba(0,243,255,0.2);'>" +
                                    "<div style='font-size:2.5rem;margin-bottom:0.5rem;'>⬇️</div>" +
                                    "<h2 style='color:#00f3ff;font-size:1.2rem;letter-spacing:2px;margin-bottom:0.5rem;margin-top:0;'>UPDATE AVAILABLE</h2>" +
                                    "<p style='color:#a0d8ef;font-size:0.9rem;margin-bottom:0.25rem;margin-top:0;'>Version <span id='global-ota-version' style='color:#00f3ff;font-weight:bold;'></span></p>" +
                                    "<p id='global-ota-notes' style='color:rgba(255,255,255,0.5);font-size:0.8rem;margin-bottom:1.5rem;margin-top:0;'></p>" +
                                    "<div style='display:flex;gap:1rem;justify-content:center;'>" +
                                        "<button onclick=\"document.getElementById('global-ota-popup').remove();\" style='padding:0.6rem 1.2rem;border:1px solid rgba(255,255,255,0.2);color:rgba(255,255,255,0.5);font-size:0.75rem;border-radius:6px;background:none;cursor:pointer;'>LATER</button>" +
                                        "<button onclick=\"document.getElementById('global-ota-popup').remove(); if(window.AndroidLauncher) window.AndroidLauncher.startUpdateDownload();\" style='padding:0.6rem 1.5rem;border:1px solid #00f3ff;background:rgba(0,243,255,0.15);color:#00f3ff;font-size:0.75rem;border-radius:6px;cursor:pointer;font-weight:bold;'>UPDATE NOW</button>" +
                                    "</div>" +
                                "</div>" +
                            "</div>";

                            String injectScript = 
                                "if (!document.getElementById('global-ota-popup')) { " +
                                    "var wrapper = document.createElement('div');" +
                                    "wrapper.innerHTML = \"" + otaPopupHtml.replace("\"", "\\\"") + "\";" +
                                    "document.body.appendChild(wrapper.firstChild);" +
                                "} " +
                                "document.getElementById('global-ota-version').innerText = '" + remoteVersionStr + "'; " +
                                "document.getElementById('global-ota-notes').innerText = '" + releaseNotes + "'; " +
                                "if (typeof window.showUpdateAvailable === 'function') { window.showUpdateAvailable('" + remoteVersionStr + "', '" + releaseNotes + "'); }";

                            runOnUiThread(() -> webView.evaluateJavascript(injectScript, null));
                        }
                    } else if (manual) {
                        Log.d("Launcher", "Up to date. Remote=" + remoteVersionStr + " Local=" + localVersionStr);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "System is up to date (v" + localVersionStr + ")", Toast.LENGTH_SHORT).show());
                    }
                } else if (manual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Update server returned: " + responseCode, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e("Launcher", "OTA update error: " + e.getMessage(), e);
                isUpdating.set(false); // Always unlock on error
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Update check failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    public void checkForApkUpdates(boolean manual) {
        if (isUpdating.get()) return;

        new Thread(() -> {
            try {
                String checkUrl = "https://raw.githubusercontent.com/nadhilrobomiracle/nova_releases/main/android_update.json?t=" + System.currentTimeMillis();
                URL url = new URL(checkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String remoteVersionStr = json.optString("version", "0");
                    String downloadUrl = json.optString("download_url", "");
                    
                    double remoteVersion = Double.parseDouble(remoteVersionStr);
                    int currentVersion = 1;
                    try {
                        currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }

                    if (remoteVersion > currentVersion && !downloadUrl.isEmpty()) {
                        pendingApkUrl = downloadUrl;
                        pendingApkVersion = remoteVersionStr;

                        if (manual || !remoteVersionStr.equals(lastNotifiedApkVersion)) {
                            lastNotifiedApkVersion = remoteVersionStr;
                            String releaseNotes = json.optString("release_notes", "Native system update available").replace("'", "\\'");
                            
                            String otaPopupHtml = "<div id='global-apk-popup' style='position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.85);z-index:9999;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(5px);font-family:sans-serif;'>" +
                                "<div style='background:rgba(4,15,30,0.95);border:1px solid rgba(255,165,0,0.5);border-radius:12px;padding:2rem;text-align:center;max-width:320px;box-shadow:0 0 30px rgba(255,165,0,0.2);'>" +
                                    "<div style='font-size:2.5rem;margin-bottom:0.5rem;'>🤖</div>" +
                                    "<h2 style='color:#ffa500;font-size:1.2rem;letter-spacing:2px;margin-bottom:0.5rem;margin-top:0;'>SYSTEM UPGRADE</h2>" +
                                    "<p style='color:#ffe0a0;font-size:0.9rem;margin-bottom:0.25rem;margin-top:0;'>Android App v<span id='global-apk-version' style='color:#ffa500;font-weight:bold;'></span></p>" +
                                    "<p id='global-apk-notes' style='color:rgba(255,255,255,0.5);font-size:0.8rem;margin-bottom:1.5rem;margin-top:0;'></p>" +
                                    "<div style='display:flex;gap:1rem;justify-content:center;'>" +
                                        "<button onclick=\"document.getElementById('global-apk-popup').remove();\" style='padding:0.6rem 1.2rem;border:1px solid rgba(255,255,255,0.2);color:rgba(255,255,255,0.5);font-size:0.75rem;border-radius:6px;background:none;cursor:pointer;'>LATER</button>" +
                                        "<button onclick=\"document.getElementById('global-apk-popup').remove(); if(window.AndroidLauncher) window.AndroidLauncher.startApkDownload();\" style='padding:0.6rem 1.5rem;border:1px solid #ffa500;background:rgba(255,165,0,0.15);color:#ffa500;font-size:0.75rem;border-radius:6px;cursor:pointer;font-weight:bold;'>DOWNLOAD & INSTALL</button>" +
                                    "</div>" +
                                "</div>" +
                            "</div>";

                            String injectScript = 
                                "if (!document.getElementById('global-apk-popup')) { " +
                                    "var wrapper = document.createElement('div');" +
                                    "wrapper.innerHTML = \"" + otaPopupHtml.replace("\"", "\\\"") + "\";" +
                                    "document.body.appendChild(wrapper.firstChild);" +
                                "} " +
                                "document.getElementById('global-apk-version').innerText = '" + remoteVersionStr + "'; " +
                                "document.getElementById('global-apk-notes').innerText = '" + releaseNotes + "'; ";

                            runOnUiThread(() -> webView.evaluateJavascript(injectScript, null));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("Launcher", "APK update error: " + e.getMessage(), e);
            }
        }).start();
    }

    public void sendPythonOutput(String text, boolean isError) {
        if (this.webView != null) {
            runOnUiThread(() -> {
                String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
                String jsFunc = isError ? "window.pythonStderr" : "window.pythonStdout";
                this.webView.evaluateJavascript(jsFunc + "(\"" + safeText + "\");", null);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAuthorized = false;
        hideSystemUI();
        
        if (isInstallAllowed()) {
            if (webView != null) {
                webView.evaluateJavascript("var el = document.getElementById('global-security-popup'); if (el) el.remove();", null);
            }
            if (wasApkDownloadBlocked) {
                wasApkDownloadBlocked = false;
                startApkDownloadIntent();
            }
        }
        
        checkPermissions();
    }

    private void hideSystemUI() {
        // Equivalent to the magic number 5894
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void setupKeyboardWorkaround() {
        View decorView = getWindow().getDecorView();
        View contentView = findViewById(android.R.id.content); // 16908290 is android.R.id.content

        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            decorView.getWindowVisibleDisplayFrame(r);
            int screenHeight = decorView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            if (keypadHeight != previousKeypadHeight) {
                previousKeypadHeight = keypadHeight;
                if (keypadHeight > screenHeight * 0.15) {
                    contentView.setPadding(0, 0, 0, keypadHeight);
                } else {
                    contentView.setPadding(0, 0, 0, 0);
                    hideSystemUI();
                }
            }
        });
    }

    private void startCameraServer() {
        new Thread(() -> {
            cameraServer = new CameraServer(8080);
            try {
                cameraServer.start();
                Log.d("Launcher", "Camera server started on port 8080");
                Log.d("Launcher", "IP: " + getDeviceIpAddress());
            } catch (Exception e) {
                Log.e("Launcher", "Could not start camera server", e);
            }
        }).start();
    }

    private void setupWebView() {
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        try {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Disable Zoom globally to feel like a native APK
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Block text selection & long press globally except for python.html and input fields
        this.webView.setOnLongClickListener(v -> {
            WebView w = (WebView) v;
            String url = w.getUrl();
            if (url != null && url.contains("python.html")) {
                return false; // allow long click for text selection in python.html
            }
            
            WebView.HitTestResult result = w.getHitTestResult();
            if (result != null && (result.getType() == WebView.HitTestResult.EDIT_TEXT_TYPE)) {
                return false; // allow for normal text inputs
            }
            
            return true; // consume the long click, blocking text selection everywhere else
        });

        this.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                MainActivity.this.runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception e) {
                        Log.e("Launcher", "Permission request error", e);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_RESULT_CODE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });

        this.errorLayout = findViewById(R.id.error_layout);
        this.btnRetry = findViewById(R.id.btn_retry);

        this.btnRetry.setOnClickListener(v -> checkNetworkAndLoad());

        this.webView.setWebViewClient(new WebViewClient() {
            boolean hasError = false;

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                this.hasError = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    this.hasError = true;
                    webView.setVisibility(View.GONE);
                    errorLayout.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!this.hasError) {
                    webView.setVisibility(View.VISIBLE);
                    errorLayout.setVisibility(View.GONE);
                }
            }
        });

        this.webView.addJavascriptInterface(new LauncherBridge(), "AndroidLauncher");
        checkNetworkAndLoad();
    }

    public boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void checkNetworkAndLoad() {
        if (!isNetworkAvailable()) {
            this.webView.setVisibility(View.GONE);
            this.errorLayout.setVisibility(View.VISIBLE);
            return;
        }
        this.errorLayout.setVisibility(View.GONE);
        this.webView.setVisibility(View.VISIBLE);
        if (this.webView.getUrl() == null || this.webView.getUrl().isEmpty()) {
            File indexFile = new File(getFilesDir() + "/web_assets", "index.html");
            this.webView.loadUrl("file://" + indexFile.getAbsolutePath());
        } else {
            this.webView.reload();
        }
    }

    private void checkPermissions() {
        if (!isInstallAllowed()) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Critical Security Permission")
                .setMessage("To perform native hardware upgrades, Nova OS must be authorized to install packages. Please enable 'Install from Unknown Sources' in Settings.\n\nThis is mandatory for system integrity.")
                .setPositiveButton("Open Settings", (dialog, which) -> openSecuritySettingsIntent())
                .setCancelable(false)
                .show();
            return;
        }

        String[] permissions = {
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA"
        };
        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            return;
        }
        this.micPermissionGranted = true;
        this.cameraPermissionGranted = true;
    }

    private boolean isInstallAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        } else {
            try {
                return Settings.Secure.getInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1;
            } catch (Settings.SettingNotFoundException e) {
                return false;
            }
        }
    }

    public void openSecuritySettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w("Launcher", "Direct package install settings failed, falling back.");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception e2) {
                    Log.w("Launcher", "Manage unknown sources failed, falling back.");
                }
            }
        }
        
        try {
            Intent intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w("Launcher", "Security settings failed, falling back to general settings.");
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e2) {
                Log.e("Launcher", "All settings intents failed.", e2);
                Toast.makeText(this, "Could not open Android settings automatically. Please open Settings manually.", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void injectSecurityWarningUI() {
        String warningHtml = "<div id='global-security-popup' style='position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(20,0,0,0.9);z-index:10000;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(8px);font-family:sans-serif;'>" +
            "<div style='background:rgba(30,5,5,0.95);border:1px solid rgba(255,50,50,0.6);border-radius:12px;padding:2rem;text-align:center;max-width:340px;box-shadow:0 0 40px rgba(255,0,0,0.3);'>" +
                "<div style='font-size:3rem;margin-bottom:0.5rem;animation:pulse 2s infinite;'>⚠️</div>" +
                "<h2 style='color:#ff4444;font-size:1.1rem;letter-spacing:1px;margin-bottom:1rem;margin-top:0;'>CRITICAL SECURITY OVERRIDE REQUIRED</h2>" +
                "<p style='color:#ffaaaa;font-size:0.85rem;line-height:1.4;margin-bottom:1.5rem;text-align:left;'>To perform native hardware upgrades, you must authorize Nova OS to install packages.<br><br><b>This is mandatory for system integrity.</b></p>" +
                "<div style='display:flex;flex-direction:column;gap:0.8rem;'>" +
                    "<button onclick=\"document.getElementById('global-security-popup').remove(); if(window.AndroidLauncher) window.AndroidLauncher.openSecuritySettings();\" style='padding:0.8rem 1.5rem;border:1px solid #ff4444;background:rgba(255,0,0,0.2);color:#ff4444;font-size:0.8rem;border-radius:6px;cursor:pointer;font-weight:bold;letter-spacing:1px;'>AUTHORIZE NOW</button>" +
                    "<button onclick=\"document.getElementById('global-security-popup').remove();\" style='padding:0.6rem;border:none;color:rgba(255,255,255,0.4);font-size:0.7rem;background:none;cursor:pointer;'>CANCEL UPGRADE</button>" +
                "</div>" +
            "</div>" +
        "</div>";

        String injectScript = 
            "if (!document.getElementById('global-security-popup')) { " +
                "var wrapper = document.createElement('div');" +
                "wrapper.innerHTML = \"" + warningHtml.replace("\"", "\\\"") + "\";" +
                "document.body.appendChild(wrapper.firstChild);" +
            "} ";

        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(injectScript, null);
        });
    }

    public void startApkDownloadIntent() {
        String dlUrl = pendingApkUrl;
        if (dlUrl == null || dlUrl.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "No pending APK update", Toast.LENGTH_SHORT).show());
            return;
        }

        if (!isInstallAllowed()) {
            wasApkDownloadBlocked = true;
            injectSecurityWarningUI();
            return;
        }

        if (isUpdating.get()) return;
        isUpdating.set(true);

        runOnUiThread(() -> Toast.makeText(this, "Downloading native upgrade in background...", Toast.LENGTH_LONG).show());

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(dlUrl));
            request.setTitle("Nova OS Upgrade");
            request.setDescription("Downloading native Android package...");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "nova_update.apk");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            final long downloadId = manager.enqueue(request);

            BroadcastReceiver onComplete = new BroadcastReceiver() {
                public void onReceive(Context ctxt, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        isUpdating.set(false);
                        Uri uri = manager.getUriForDownloadedFile(downloadId);
                        if (uri != null) {
                            Intent installIntent = new Intent(Intent.ACTION_VIEW);
                            installIntent.setDataAndType(uri, "application/vnd.android.package-archive");
                            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try {
                                startActivity(installIntent);
                            } catch (Exception e) {
                                Log.e("Launcher", "Error installing APK", e);
                                Toast.makeText(MainActivity.this, "Installation blocked. Please check permissions.", Toast.LENGTH_LONG).show();
                            }
                        }
                        unregisterReceiver(this);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(this, onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
            } else {
                registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            }
        } catch (Exception e) {
            isUpdating.set(false);
            Log.e("Launcher", "Error starting APK download", e);
            runOnUiThread(() -> Toast.makeText(this, "Error starting download: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            this.speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            this.speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onAndroidSpeechError) window.onAndroidSpeechError(" + error + ");", null);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && webView != null) {
                        webView.evaluateJavascript("if(window.onAndroidSpeechResult) window.onAndroidSpeechResult('" + matches.get(0).replace("'", "\\'") + "');", null);
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startNativeSpeech() {
        if (this.speechRecognizer == null) {
            setupSpeechRecognizer();
        }
        if (this.speechRecognizer != null) {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            this.speechRecognizer.startListening(intent);
            return;
        }
        Toast.makeText(this, "Speech Recognition not available", Toast.LENGTH_SHORT).show();
        if (this.webView != null) {
            this.webView.evaluateJavascript("if(window.onAndroidSpeechError) window.onAndroidSpeechError(-1);", null);
        }
    }

    private void setMaleVoice() {
        if (this.textToSpeech != null) {
            Voice fallbackVoice = null;
            try {
                for (Voice voice : this.textToSpeech.getVoices()) {
                    String voiceName = voice.getName().toLowerCase();
                    if (voiceName.contains("male") && !voiceName.contains("female")) {
                        this.textToSpeech.setVoice(voice);
                        this.textToSpeech.setPitch(1.0f);
                        return;
                    } else if (voice.getLocale().getLanguage().startsWith("en")) {
                        fallbackVoice = voice;
                    }
                }
                if (fallbackVoice != null) {
                    this.textToSpeech.setVoice(fallbackVoice);
                }
                this.textToSpeech.setPitch(0.8f);
                Log.d("Launcher", "No explicit male voice found, using fallback with lower pitch");
            } catch (Exception e) {
                Log.e("Launcher", "Error setting male voice", e);
                this.textToSpeech.setPitch(0.8f);
            }
        }
    }

    private void changeTtsVoice(String voiceName) {
        if (this.textToSpeech != null) {
            try {
                for (Voice voice : this.textToSpeech.getVoices()) {
                    if (voice.getName().equals(voiceName)) {
                        this.textToSpeech.setVoice(voice);
                        Log.d("Launcher", "Voice changed to: " + voiceName);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e("Launcher", "Error changing voice", e);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean newlyGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals("android.permission.RECORD_AUDIO")) {
                    if (!this.micPermissionGranted && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        newlyGranted = true;
                    }
                    this.micPermissionGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                } else if (permissions[i].equals("android.permission.CAMERA")) {
                    if (!this.cameraPermissionGranted && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        newlyGranted = true;
                    }
                    this.cameraPermissionGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                }
            }
            if (newlyGranted && this.webView != null) {
                runOnUiThread(() -> this.webView.reload());
            }
        }
    }

    private class LauncherBridge {
        @JavascriptInterface
        public void speak(String text) {
            if (isTtsInitialized && textToSpeech != null) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FLUSH_" + System.currentTimeMillis());
            }
        }

        @JavascriptInterface
        public void speakQueue(String text) {
            if (isTtsInitialized && textToSpeech != null) {
                // Use native Android TTS with sentence buffering
                textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "QUEUE_" + System.currentTimeMillis());
            }
        }

        @JavascriptInterface
        public boolean isNativeTtsAvailable() {
            return isTtsInitialized;
        }

        @JavascriptInterface
        public void triggerUpdateCheck() {
            checkForOTAUpdates(true);
            checkForApkUpdates(true);
        }

        @JavascriptInterface
        public String getInstalledVersion() {
            SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
            return prefs.getString("ota_version_str", "1.0");
        }

        @JavascriptInterface
        public void startUpdateDownload() {
            String dlUrl = pendingUpdateUrl;
            String dlVersion = pendingUpdateVersion;
            if (dlUrl == null || dlUrl.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No pending update", Toast.LENGTH_SHORT).show());
                return;
            }
            if (isUpdating.get()) return;
            isUpdating.set(true);

            new Thread(() -> {
                try {
                    File updateHtml = new File(getFilesDir() + "/web_assets", "update.html");
                    String updateUrl = updateHtml.exists()
                        ? "file://" + updateHtml.getAbsolutePath()
                        : "file:///android_asset/update.html";
                    runOnUiThread(() -> webView.loadUrl(updateUrl));
                    Thread.sleep(2500);
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window.updateProgress) window.updateProgress(0, 'Connecting to server...');", null));
                    Thread.sleep(300);

                    String zipDownloadUrl = dlUrl.contains("?")
                        ? dlUrl + "&t=" + System.currentTimeMillis()
                        : dlUrl + "?t=" + System.currentTimeMillis();

                    URL zipUrl = new URL(zipDownloadUrl);
                    HttpURLConnection zipConn = (HttpURLConnection) zipUrl.openConnection();
                    zipConn.setRequestMethod("GET");
                    zipConn.setRequestProperty("Cache-Control", "no-cache");

                    int contentLength = zipConn.getContentLength();
                    File zipFile = new File(getFilesDir(), "update.zip");
                    try (InputStream is = zipConn.getInputStream();
                         OutputStream os = new FileOutputStream(zipFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long total = 0;
                        while ((read = is.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                            total += read;
                            if (contentLength > 0) {
                                final int pct = (int) ((total * 100) / contentLength);
                                runOnUiThread(() -> webView.evaluateJavascript(
                                    "if(window.updateProgress) window.updateProgress(" + pct + ", 'Downloading Package...');", null));
                            }
                        }
                    }

                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window.updateProgress) window.updateProgress(100, 'Extracting Files...');", null));
                    Thread.sleep(500);

                    File webAssetsDir = new File(getFilesDir(), "web_assets");
                    UnzipUtils.unzip(zipFile, webAssetsDir);
                    zipFile.delete();

                    SharedPreferences prefs = getSharedPreferences("NovaPrefs", MODE_PRIVATE);
                    prefs.edit().putString("ota_version_str", dlVersion).apply();
                    pendingUpdateUrl = null;
                    pendingUpdateVersion = null;

                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window.updateProgress) window.updateProgress(100, 'Rebooting System...');", null));
                    Thread.sleep(1500);

                    runOnUiThread(() -> {
                        File indexFile = new File(getFilesDir() + "/web_assets", "index.html");
                        webView.loadUrl("file://" + indexFile.getAbsolutePath());
                    });
                } catch (Exception e) {
                    Log.e("Launcher", "Update download failed: " + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                } finally {
                    isUpdating.set(false);
                }
            }).start();
        }

        @JavascriptInterface
        public void startApkDownload() {
            MainActivity.this.startApkDownloadIntent();
        }

        @JavascriptInterface
        public void openSecuritySettings() {
            MainActivity.this.openSecuritySettingsIntent();
        }

        // Restored from the JADX bytecode dump

        @JavascriptInterface
        public void startLocalModelDownload(String urlStr) {
            if (isModelDownloading.get()) return;
            isModelDownloading.set(true);

            new Thread(() -> {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    String contentLengthStr = conn.getHeaderField("Content-Length");
                    long contentLength = -1;
                    if (contentLengthStr != null && !contentLengthStr.isEmpty()) {
                        try {
                            contentLength = Long.parseLong(contentLengthStr);
                        } catch (NumberFormatException ignored) {}
                    }
                    
                    File modelFile = new File(getFilesDir(), "local_model.litertlm");
                    try (InputStream is = conn.getInputStream();
                         OutputStream os = new FileOutputStream(modelFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long total = 0;
                        int lastPct = -1;
                        while ((read = is.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                            total += read;
                            if (contentLength > 0) {
                                final int pct = (int) ((total * 100) / contentLength);
                                if (pct != lastPct && pct <= 100) {
                                    lastPct = pct;
                                    runOnUiThread(() -> webView.evaluateJavascript(
                                        "if(window.onModelDownloadProgress) window.onModelDownloadProgress(" + pct + ");", null));
                                }
                            }
                        }
                    }

                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window.onModelDownloadProgress) window.onModelDownloadProgress(100);", null));
                } catch (Exception e) {
                    Log.e("Launcher", "Model download failed", e);
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window.onModelDownloadError) window.onModelDownloadError('" + e.getMessage() + "');", null));
                } finally {
                    isModelDownloading.set(false);
                }
            }).start();
        }

        @JavascriptInterface
        public boolean checkLocalModelExists() {
            File modelFile = new File(getFilesDir(), "local_model.litertlm");
            return modelFile.exists();
        }

        @JavascriptInterface
        public void initLocalModel(boolean useGpu) {
            File modelFile = new File(getFilesDir(), "local_model.litertlm");
            if (!modelFile.exists()) {
                runOnUiThread(() -> webView.evaluateJavascript("if(window.onModelInitError) window.onModelInitError('Model not downloaded');", null));
                return;
            }

            localAiEngine.initialize(modelFile.getAbsolutePath(), useGpu, new LocalAiEngine.AiCallback() {
                @Override
                public void onChunk(String text) {}

                @Override
                public void onDone() {
                    runOnUiThread(() -> webView.evaluateJavascript("if(window.onModelInitSuccess) window.onModelInitSuccess();", null));
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> webView.evaluateJavascript("if(window.onModelInitError) window.onModelInitError('" + error + "');", null));
                }
            });
        }

        @JavascriptInterface
        public void updateLocalAiPersona(String personaText) {
            localAiEngine.updateSystemInstruction(personaText);
        }

        private long lastCpuTime = 0;
        private long lastTimeMillis = 0;

        @JavascriptInterface
        public int getRealCpuLoad() {
            try {
                long cpuTime = android.os.Process.getElapsedCpuTime();
                long now = System.currentTimeMillis();
                if (lastTimeMillis == 0) {
                    lastCpuTime = cpuTime;
                    lastTimeMillis = now;
                    return 5;
                }
                long cpuDiff = cpuTime - lastCpuTime;
                long timeDiff = now - lastTimeMillis;
                lastCpuTime = cpuTime;
                lastTimeMillis = now;
                
                int numCores = Runtime.getRuntime().availableProcessors();
                // Process elapsed CPU time is in milliseconds
                int usage = (int) (((float) cpuDiff / Math.max(timeDiff, 1)) * 100.0f / numCores);
                return Math.max(0, Math.min(usage, 100));
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public void sendLocalAiMessage(String prompt) {
            localAiEngine.generateStream(prompt, new LocalAiEngine.AiCallback() {
                @Override
                public void onChunk(String text) {
                    String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
                    runOnUiThread(() -> webView.evaluateJavascript("if(window.onAiMessageChunk) window.onAiMessageChunk(\"" + safeText + "\");", null));
                }

                @Override
                public void onDone() {
                    runOnUiThread(() -> webView.evaluateJavascript("if(window.onAiMessageDone) window.onAiMessageDone();", null));
                }

                @Override
                public void onError(String error) {
                    String safeError = error.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
                    runOnUiThread(() -> webView.evaluateJavascript("if(window.onAiMessageError) window.onAiMessageError(\"" + safeError + "\");", null));
                }
            });
        }

        @JavascriptInterface
        public void stopLocalAiMessage() {
            if (localAiEngine != null) {
                localAiEngine.cancelGeneration();
            }
        }

        @JavascriptInterface
        public String getAllVoices() {
            if (!isTtsInitialized || textToSpeech == null) {
                return "[]";
            }
            try {
                JSONArray jsonArray = new JSONArray();
                Voice currentVoice = textToSpeech.getVoice();
                String currentVoiceName = currentVoice != null ? currentVoice.getName() : "";

                for (Voice voice : textToSpeech.getVoices()) {
                    if (voice.getLocale().getLanguage().startsWith("en")) {
                        JSONObject voiceObj = new JSONObject();
                        String nameLower = voice.getName().toLowerCase();

                        boolean isFemale = nameLower.contains("female") || nameLower.contains("woman");
                        boolean isMale = nameLower.contains("male") || nameLower.contains("man");

                        String genderStr = "Unknown";
                        if (isMale) genderStr = "Male";
                        else if (isFemale) genderStr = "Female";

                        voiceObj.put("name", formatVoiceName(voice.getName()));
                        voiceObj.put("locale", voice.getLocale().getDisplayLanguage());
                        voiceObj.put("id", voice.getName());
                        voiceObj.put("gender", genderStr);
                        voiceObj.put("isActive", voice.getName().equals(currentVoiceName));

                        jsonArray.put(voiceObj);
                    }
                }
                return jsonArray.toString();
            } catch (Exception e) {
                Log.e("Launcher", "Error fetching voices", e);
                return "[]";
            }
        }

        private String formatVoiceName(String rawName) {
            if (rawName == null) return "Unknown Voice";
            String[] words = rawName.replace("-x-", " ")
                    .replace("-local", " Local")
                    .replace("-network", " Network")
                    .replace("-", " ")
                    .split(" ");

            StringBuilder sb = new StringBuilder();
            for (String word : words) {
                if (word.length() > 0) {
                    if (word.length() <= 2) {
                        sb.append(word.toUpperCase()).append(" ");
                    } else {
                        sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
                    }
                }
            }
            return sb.toString().trim();
        }

        @JavascriptInterface
        public void setVoice(String voiceName) {
            runOnUiThread(() -> changeTtsVoice(voiceName));
        }

        @JavascriptInterface
        public void startAndroidVoiceRecognition() {
            runOnUiThread(() -> startNativeSpeech());
        }

        @JavascriptInterface
        public boolean hasMicPermission() {
            return micPermissionGranted;
        }

        @JavascriptInterface
        public boolean isNetworkAvailable() {
            return MainActivity.this.isNetworkAvailable();
        }

        @JavascriptInterface
        public void triggerNetworkError() {
            runOnUiThread(() -> {
                webView.setVisibility(View.GONE);
                errorLayout.setVisibility(View.VISIBLE);
            });
        }

        @JavascriptInterface
        public void openApp(String packageName) {
            if ("com.android.settings".equals(packageName)) {
                File lockFile = new File(getFilesDir() + "/web_assets", "lock.html");
                runOnUiThread(() -> webView.loadUrl("file://" + lockFile.getAbsolutePath()));
                return;
            }
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Toast.makeText(MainActivity.this, "App not found", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e("Launcher", "Error opening app", e);
            }
        }

        @JavascriptInterface
        public void setAuthorized(boolean authorized) {
            MainActivity.setAuthorized(authorized);
        }

        @JavascriptInterface
        public void openWifiSettings() {
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        }

        @JavascriptInterface
        public void openSettings() {
            File lockFile = new File(getFilesDir() + "/web_assets", "lock.html");
            runOnUiThread(() -> webView.loadUrl("file://" + lockFile.getAbsolutePath()));
        }

        @JavascriptInterface
        public void bypassSettingsLock() {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public String getIpAddress() {
            return getDeviceIpAddress();
        }

        @JavascriptInterface
        public boolean isCameraServerRunning() {
            return cameraServer != null && cameraServer.wasStarted();
        }

        @JavascriptInterface
        public int getCameraServerPort() {
            return 8080;
        }

        @JavascriptInterface
        public void stopCamera() {
            if (cameraServer != null) {
                cameraServer.stopCamera();
            }
        }

        @JavascriptInterface
        public void startCamera() {
            if (cameraServer != null) {
                new Thread(() -> {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:8080/on").openConnection();
                        conn.setRequestMethod("GET");
                        conn.getInputStream().close();
                    } catch (Exception e) {
                        Log.e("Launcher", "Error starting camera via URL", e);
                    }
                }).start();
            }
        }

        @JavascriptInterface
        public void restartCamera() {
            if (cameraServer != null) {
                new Thread(() -> {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:8080/restart").openConnection();
                        conn.setRequestMethod("GET");
                        conn.getInputStream().close();
                    } catch (Exception e) {
                        Log.e("Launcher", "Error restarting camera via URL", e);
                    }
                }).start();
            }
        }

        @JavascriptInterface
        public void getApps() {
            runOnUiThread(() -> loadAppsIntoWebView());
        }

        @JavascriptInterface
        public void saveRobotIP(String ip) {
            getSharedPreferences("NovaPrefs", MODE_PRIVATE).edit().putString("robot_ip", ip).apply();
            Log.d("Launcher", "Saved Robot IP: " + ip);
        }

        @JavascriptInterface
        public String getSavedRobotIP() {
            return getSharedPreferences("NovaPrefs", MODE_PRIVATE).getString("robot_ip", "nova-robot.local");
        }

        @JavascriptInterface
        public void runPythonScript(String code) {
            new Thread(() -> {
                try {
                    Python py = Python.getInstance();
                    String sysOverride = "import sys\n" +
                        "import java\n" +
                        "class StdoutRedirector:\n" +
                        "    def write(self, s):\n" +
                        "        java.jclass('com.nova.launcher.MainActivity').getInstance().sendPythonOutput(str(s), False)\n" +
                        "    def flush(self):\n" +
                        "        pass\n" +
                        "class StderrRedirector:\n" +
                        "    def write(self, s):\n" +
                        "        java.jclass('com.nova.launcher.MainActivity').getInstance().sendPythonOutput(str(s), True)\n" +
                        "    def flush(self):\n" +
                        "        pass\n" +
                        "sys.stdout = StdoutRedirector()\n" +
                        "sys.stderr = StderrRedirector()\n" +
                        "try:\n" +
                        "    import java.android.importer\n" +
                        "    if hasattr(java.android.importer, 'AssetPath') and not hasattr(java.android.importer.AssetPath, 'parent'):\n" +
                        "        import pathlib\n" +
                        "        java.android.importer.AssetPath.parent = property(lambda self: pathlib.Path(str(self)).parent)\n" +
                        "except Exception:\n" +
                        "    pass\n" +
                        "try:\n" +
                        "    import socket\n" +
                        "    if not hasattr(socket, '_original_getaddrinfo'):\n" +
                        "        socket._original_getaddrinfo = socket.getaddrinfo\n" +
                        "        def custom_getaddrinfo(host, port, *args, **kwargs):\n" +
                        "            if host == 'nova-robot.local':\n" +
                        "                try:\n" +
                        "                    import java\n" +
                        "                    ctx = java.jclass('com.nova.launcher.MainActivity').getInstance()\n" +
                        "                    ip = ctx.getSharedPreferences('NovaPrefs', 0).getString('robot_ip', 'nova-robot.local')\n" +
                        "                    if ip and ip != 'nova-robot.local':\n" +
                        "                        host = ip\n" +
                        "                except Exception:\n" +
                        "                    pass\n" +
                        "            return socket._original_getaddrinfo(host, port, *args, **kwargs)\n" +
                        "        socket.getaddrinfo = custom_getaddrinfo\n" +
                        "except Exception:\n" +
                        "    pass";
                    com.chaquo.python.PyObject globals = py.getModule("builtins").callAttr("dict");
                    py.getModule("builtins").callAttr("exec", sysOverride, globals);

                    // Save the script to a real file on the Android device
                    java.io.File dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File scriptFile = new java.io.File(dir, "app.py");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(scriptFile);
                    fos.write(code.getBytes("UTF-8"));
                    fos.close();
                    
                    String pathScript = "import sys\n" +
                        "import os\n" +
                        "import runpy\n" +
                        "lib_path = '" + dir.getAbsolutePath() + "/python_libs'\n" +
                        "if not os.path.exists(lib_path):\n" +
                        "    os.makedirs(lib_path)\n" +
                        "if lib_path not in sys.path:\n" +
                        "    sys.path.insert(0, lib_path)\n" +
                        "os.chdir('" + dir.getAbsolutePath() + "')\n" +
                        "try:\n" +
                        "    runpy.run_path('" + scriptFile.getAbsolutePath() + "', run_name='__main__')\n" +
                        "except Exception as e:\n" +
                        "    import traceback\n" +
                        "    traceback.print_exc()";
                    py.getModule("builtins").callAttr("exec", pathScript, globals);
                } catch (Exception e) {
                    sendPythonOutput(e.toString() + "\n", true);
                }
            }).start();
        }

        @JavascriptInterface
        public void installPythonPackage(String packageName) {
            new Thread(() -> {
                try {
                    sendPythonOutput("Installing " + packageName + "...\n", false);
                    Python py = Python.getInstance();
                    String pipScript = "import sys\n" +
                        "import os\n" +
                        "from pip._internal.cli.main import main as pip_main\n" +
                        "lib_path = '" + getFilesDir().getAbsolutePath() + "/python_libs'\n" +
                        "if not os.path.exists(lib_path):\n" +
                        "    os.makedirs(lib_path)\n" +
                        "pip_main(['install', '" + packageName + "', '--target', lib_path])";
                    com.chaquo.python.PyObject globals = py.getModule("builtins").callAttr("dict");
                    py.getModule("builtins").callAttr("exec", pipScript, globals);
                    sendPythonOutput("Finished installing " + packageName + "\n", false);
                } catch (Exception e) {
                    sendPythonOutput("Error installing " + packageName + ": " + e.toString() + "\n", true);
                }
            }).start();
        }

        @JavascriptInterface
        public void listPythonPackages() {
            new Thread(() -> {
                try {
                    Python py = Python.getInstance();
                    String listScript = "import sys\n" +
                        "import importlib.metadata\n" +
                        "import java\n" +
                        "output = 'Package                  Version\\n------------------------ -------\\n'\n" +
                        "try:\n" +
                        "    dists = sorted(importlib.metadata.distributions(), key=lambda d: d.metadata['Name'].lower())\n" +
                        "    for dist in dists:\n" +
                        "        output += f\"{dist.metadata['Name']:<24} {dist.version}\\n\"\n" +
                        "except Exception as e:\n" +
                        "    output += str(e) + '\\n'\n" +
                        "java.jclass('com.nova.launcher.MainActivity').getInstance().sendPythonOutput(output, False)";
                    com.chaquo.python.PyObject globals = py.getModule("builtins").callAttr("dict");
                    py.getModule("builtins").callAttr("exec", listScript, globals);
                } catch (Exception e) {
                    sendPythonOutput("Error listing packages: " + e.toString() + "\n", true);
                }
            }).start();
        }
    }

    private void loadAppsIntoWebView() {
        String appsJson = fetchInstalledApps();
        if (this.webView != null) {
            this.webView.evaluateJavascript("window.receiveRealApps(" + JSONObject.quote(appsJson) + ");", null);
        }
    }

    private String fetchInstalledApps() {
        JSONArray appsArray = new JSONArray();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);

        // Sorting the apps (and moving YouTube to the top based on original lambda logic)
        Collections.sort(apps, (a, b) -> {
            String pkgA = a.activityInfo.packageName;
            String pkgB = b.activityInfo.packageName;
            if (pkgA.equals("com.google.android.youtube")) return -1;
            if (pkgB.equals("com.google.android.youtube")) return 1;
            return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
        });

        for (ResolveInfo resolveInfo : apps) {
            try {
                JSONObject appObj = new JSONObject();
                String appName = resolveInfo.loadLabel(pm).toString();
                String packageName = resolveInfo.activityInfo.packageName;

                // Filtering out unwanted/system apps as done originally
                if (!packageName.equals("com.android.vending") &&
                    !packageName.equals("com.miui.securitycenter") &&
                    !packageName.equals("com.miui.miservice") &&
                    !packageName.equals("com.android.thememanager") &&
                    !packageName.contains("feedback") &&
                    !packageName.contains("bugreport")) {

                    if (!packageName.contains("com.google.android.gms") || packageName.equals("com.google.android.youtube")) {
                        appObj.put("name", appName);
                        appObj.put("pkg", packageName);

                        Bitmap bitmap = drawableToBitmap(resolveInfo.loadIcon(pm));
                        if (bitmap != null) {
                            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, 96, 96, true);
                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 70, outputStream);
                            appObj.put("iconBase64", Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP));
                        }
                        appsArray.put(appObj);
                    }
                }
            } catch (Exception e) {
                Log.e("Launcher", "Error loading app", e);
            }
        }
        return appsArray.toString();
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int height = 128;
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 128;
        if (drawable.getIntrinsicHeight() > 0) {
            height = drawable.getIntrinsicHeight();
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    public String getDeviceIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) {
                return "Unknown";
            }
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                return "Not Connected";
            }
            return String.format(Locale.US, "%d.%d.%d.%d",
                    (ip & 0xff),
                    (ip >> 8 & 0xff),
                    (ip >> 16 & 0xff),
                    (ip >> 24 & 0xff));
        } catch (Exception e) {
            Log.e("Launcher", "Error getting IP address", e);
            return "Error";
        }
    }

    @Override
    protected void onDestroy() {
        stopOTAPolling();
        if (this.cameraServer != null) {
            this.cameraServer.stopCamera();
            this.cameraServer.stop();
        }
        if (this.speechRecognizer != null) {
            this.speechRecognizer.destroy();
        }
        if (this.textToSpeech != null) {
            this.textToSpeech.stop();
            this.textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (this.fileUploadCallback != null) {
                this.fileUploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                this.fileUploadCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "if(typeof goBackAndStop === 'function') { goBackAndStop(); } else if(window.history.length > 1) { window.history.back(); } else { window.location.href='index.html'; }", 
                null
            );
        } else {
            super.onBackPressed();
        }
    }
}
