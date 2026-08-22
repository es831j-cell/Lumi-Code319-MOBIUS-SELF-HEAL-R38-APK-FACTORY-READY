package com.distressedelk.lumi;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Interim Lumi 1.0 write-authorization gate.
 * Captured voice is not treated as a biometric matcher. Until production speaker verification
 * is installed, maintenance writes require completed admin enrollment, an unlocked device, and
 * a current explicit approval phrase in the same user turn.
 */
final class MaintenanceAuthorization {
    static final class Decision {
        final boolean allowed; final String reason;
        Decision(boolean allowed,String reason){this.allowed=allowed;this.reason=reason;}
    }
    private MaintenanceAuthorization(){}

    static Decision authorizeWrite(Activity activity, SharedPreferences prefs, String userText) {
        if (prefs.getBoolean("remote_maintenance_revoked", false)) return new Decision(false,"Remote/AI maintenance has been revoked in Lumi settings.");
        if (!prefs.getBoolean("admin_enrollment_complete", false)) return new Decision(false,"Administrator enrollment must be completed before Lumi accepts maintenance writes.");
        if (!IdentityHierarchy.adminSessionActive(prefs)) return new Decision(false,"Root administrator authority is not active. Say the administrator passphrase first, then approve the change.");
        try {
            KeyguardManager km=(KeyguardManager)activity.getSystemService(Context.KEYGUARD_SERVICE);
            if(km!=null && km.isDeviceLocked()) return new Decision(false,"The phone is locked. Unlock it before approving a maintenance change.");
        } catch(Throwable ignored) {}
        String l=userText==null?"":userText.toLowerCase(Locale.US).trim();
        boolean explicit = l.matches(".*\\b(do it|go ahead|install it|install the update|apply it|apply the update|update lumi|fix it|make the change|proceed|approved|approve it)\\b.*");
        if(!explicit) return new Decision(false,"A current explicit owner approval is required for this write action. Say something like ‘do it’ or ‘install the update.’");
        return new Decision(true,"Sole root administrator session active, device unlocked, and explicit current approval received.");
    }
}
