package com.distressedelk.lumi.guardian;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

final class GuardianBridgeClient {
    private static final Uri URI = Uri.parse("content://" + TrustedIdentity.BRIDGE_AUTHORITY);
    private GuardianBridgeClient() {}

    static Bundle call(Context context, String method) { return call(context, method, null); }

    static Bundle call(Context context, String method, Bundle extras) {
        try {
            Bundle b = context.getContentResolver().call(URI, method, null, extras);
            return b == null ? new Bundle() : b;
        } catch (Exception e) {
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            return b;
        }
    }
}
