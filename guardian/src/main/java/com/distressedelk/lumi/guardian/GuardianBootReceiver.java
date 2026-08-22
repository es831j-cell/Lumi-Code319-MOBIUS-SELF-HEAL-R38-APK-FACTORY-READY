package com.distressedelk.lumi.guardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GuardianBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        GuardianLedger.append(context, "GUARDIAN_BOOT", "action=" + String.valueOf(intent.getAction()));
    }
}
