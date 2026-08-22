package com.distressedelk.lumi;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.BatteryManager;
import java.net.HttpURLConnection;
import java.net.URL;

public class ModelMaintenanceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("lumi", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("overnight_maintenance", true)) return;
        // Latency-tuning build: keep the Fast Brain as the only loaded local session.
        // This hidden capability flag remains false until safe single-engine model switching ships.
        if (!prefs.getBoolean("deep_model_switching_enabled", false)) return;
        if (!readyForHeavyWork(context)) { ModelMaintenanceScheduler.retryInOneHour(context); return; }

        final PendingResult pending = goAsync();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(MainActivity.LOCAL_MODEL_URL);
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code >= 200 && code < 400) {
                    String tag = c.getHeaderField("ETag");
                    if (tag == null || tag.trim().isEmpty()) tag = c.getHeaderField("Last-Modified");
                    if (tag == null || tag.trim().isEmpty()) tag = String.valueOf(c.getContentLengthLong());
                    String old = prefs.getString("model_remote_tag", "");
                    if (old.isEmpty()) {
                        prefs.edit().putString("model_remote_tag", tag).putLong("model_last_check", System.currentTimeMillis()).apply();
                    } else if (!old.equals(tag) && prefs.getLong("model_candidate_download_id", -1L) <= 0) {
                        enqueueCandidate(context, prefs, tag);
                    } else {
                        prefs.edit().putLong("model_last_check", System.currentTimeMillis()).apply();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
                pending.finish();
            }
        }, "LumiModelCheck").start();
    }

    private static boolean readyForHeavyWork(Context context) {
        try {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int pct = scale > 0 ? level * 100 / scale : -1;
            if (plugged == 0 || pct < 95) return false;
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
        } catch (Exception e) { return false; }
    }

    private static void enqueueCandidate(Context context, SharedPreferences prefs, String tag) {
        try {
            java.io.File base=context.getExternalFilesDir(null);
            if(base!=null){ java.io.File candidate=new java.io.File(base,"models/Qwen3-4B-Q4_K_M.candidate.gguf"); if(candidate.exists()) candidate.delete(); }
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(MainActivity.LOCAL_MODEL_URL));
            r.setTitle("Lumi model maintenance");
            r.setDescription("Updating local brain while charging");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setAllowedOverMetered(false);
            r.setAllowedOverRoaming(false);
            r.setDestinationInExternalFilesDir(context, null, "models/Qwen3-4B-Q4_K_M.candidate.gguf");
            long id = dm.enqueue(r);
            prefs.edit().putLong("model_candidate_download_id", id)
                    .putString("model_candidate_tag", tag)
                    .putLong("model_last_check", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }
}
