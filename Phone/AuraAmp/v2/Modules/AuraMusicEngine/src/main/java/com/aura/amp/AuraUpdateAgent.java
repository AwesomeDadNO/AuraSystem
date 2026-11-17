package com.aura.amp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class AuraUpdateAgent {
    private static final String TAG = "AuraUpdate";
    private static final String PREFS = "aura_updates";
    private static final long CHECK_INTERVAL_MS = 30L * 60 * 1000;
    private final Activity activity;
    private final String feedUrl;
    private long downloadId = -1;
    private String expectedHash;
    private BroadcastReceiver receiver;
    private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean(false);

    public AuraUpdateAgent(Activity activity, String feedUrl) {
        this.activity = activity;
        this.feedUrl = feedUrl == null ? "" : feedUrl.trim();
    }

    public void checkIfDue() { check(false); }
    public void checkNow() { check(true); }

    private void message(String text) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new AlertDialog.Builder(activity).setTitle("AURA AMP Updates").setMessage(text)
                .setPositiveButton("OK", (dialog, which) -> {}).show();
        });
    }
    private void check(boolean manual) {
        if (!isHttps(feedUrl)) {
            if (manual) message("Update feed is not configured in this build.");
            return;
        }
        if (activity.isFinishing() || !checking.compareAndSet(false, true)) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
            checking.set(false);return;
        }
        prefs.edit().putLong("last_check", now).apply();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(feedUrl).openConnection();
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(9000);
                conn.setInstanceFollowRedirects(false);
                if (conn.getResponseCode() != 200) {if (manual) message("Unable to check for updates. Please try again later.");return;}
                byte[] data;
                try (InputStream in = conn.getInputStream()) {
                    data = readLimited(in, 8192);
                }
                JSONObject feed = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                if (!activity.getPackageName().equals(feed.optString("packageName"))) {if (manual) message("Update feed does not match the installed AURA AMP package.");return;}
                int next = feed.optInt("versionCode", 0);
                if (next <= getInstalledVersionCode()) {if (manual) message("AURA AMP is up to date.");return;}
                String apkUrl = feed.optString("apkUrl", "");
                String hash = feed.optString("sha256", "").toLowerCase(Locale.ROOT);
                if (!isHttps(apkUrl) || !hash.matches("[0-9a-f]{64}")) {if (manual) message("Update metadata is incomplete or invalid.");return;}
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity)
                        .setTitle("AURA update available")
                        .setMessage("A new version is available. Download and install now? Android will ask you to confirm the installation.")
                        .setNegativeButton("Later", (d, w) -> {})
                        .setPositiveButton("Update", (d, w) -> download(apkUrl, hash, next))
                        .show();
                });
            } catch (Exception e) {
                Log.w(TAG, "Update check unavailable: " + e.getClass().getSimpleName());
                if (manual) message("Could not check for updates. Verify your internet connection.");
            } finally {
                if (conn != null) conn.disconnect();
                checking.set(false);
            }
        }, "aura-update-check").start();
    }

    private long getInstalledVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
            return activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    private void download(String apkUrl, String sha256, int code) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setAllowedOverMetered(true);
            request.setTitle("AURA update");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                "aura-update-" + code + ".apk");
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) return;
            expectedHash = sha256;
            downloadId = manager.enqueue(request);
            if (receiver == null) {
                receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()) &&
                                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                            verifyAndInstall(manager);
                        }
                    }
                };
                IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33)
                    activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else activity.registerReceiver(receiver, filter);
            }
        } catch (Exception e) { Log.e(TAG, "Unable to start update download", e); }
    }

    private void verifyAndInstall(DownloadManager manager) {
        Uri uri = manager.getUriForDownloadedFile(downloadId);
        if (uri == null) return;
        String expected = expectedHash;
        new Thread(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                if (!hex.toString().equals(expected)) {
                    Log.e(TAG, "Downloaded APK SHA-256 mismatch; refusing install");
                    return;
                }
                activity.runOnUiThread(() -> installWithPermission(uri));
            } catch (Exception e) { Log.e(TAG, "Unable to verify update", e); }
        }, "aura-update-verify").start();
    }

    private Uri waitingForPermission;
    private void installWithPermission(Uri uri) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = uri;
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(permission);
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception e) { Log.e(TAG, "Android installer not available", e); }
    }

    public void onResume() {
        if (waitingForPermission != null && (Build.VERSION.SDK_INT < 26 ||
            activity.getPackageManager().canRequestPackageInstalls())) {
            Uri pending = waitingForPermission;
            waitingForPermission = null;
            installWithPermission(pending);
        }
    }

    public void close() {
        if (receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }

    private static boolean isHttps(String url) {
        try {
            URL u = new URL(url);
            return "https".equalsIgnoreCase(u.getProtocol()) &&
                u.getHost() != null && !u.getHost().isEmpty() &&
                u.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }

    private static byte[] readLimited(InputStream stream, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = stream.read(buffer)) != -1) {
            if (out.size() + n > max) throw new java.io.IOException("Update feed too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
