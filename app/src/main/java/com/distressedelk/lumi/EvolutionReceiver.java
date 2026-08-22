package com.distressedelk.lumi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Wakes the bounded optimization evaluator while the phone remains fully charged. */
public final class EvolutionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences p=context.getSharedPreferences("lumi",Context.MODE_PRIVATE);
        String action=intent==null?"":intent.getAction();
        if (Intent.ACTION_POWER_CONNECTED.equals(action) || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            EvolutionEngine.onPowerStateChanged(context);
            return;
        }
        if (EvolutionEngine.isCycleAction(intent)) EvolutionEngine.runCycle(context,p,"everything",false);
    }
}
