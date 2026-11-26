package com.mintfox.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 101;
    private static final int REQ_NOTIF = 102;
    private static final String HOME = "file:///android_asset/index.html";

    private FrameLayout root;
    private WebView wv;
    private boolean started = false;
    private boolean awaitingSettings = false;
    private boolean closing = false;
    private Bundle savedWebState;

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
                    "Storage permission is required. MintFox will close.",
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

        // jalankan service scan
        Intent svc = new Intent(this, ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

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
