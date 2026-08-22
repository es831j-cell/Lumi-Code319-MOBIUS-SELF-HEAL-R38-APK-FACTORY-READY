package com.distressedelk.lumi;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class ModelDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        SharedPreferences prefs = context.getSharedPreferences("lumi", Context.MODE_PRIVATE);
        long candidate = prefs.getLong("model_candidate_download_id", -2L);
        if (id == candidate) {
            prefs.edit().remove("model_candidate_download_id").putBoolean("model_candidate_ready", true).apply();
        }
    }
}
