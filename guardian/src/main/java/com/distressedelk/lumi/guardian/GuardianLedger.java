package com.distressedelk.lumi.guardian;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class GuardianLedger {
    private GuardianLedger() {}
    static synchronized void append(Context context, String event, String detail) {
        try {
            File dir = new File(context.getFilesDir(), "ledger");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "guardian-ledger.log");
            String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
            String line = ts + " | " + event + " | " + detail.replace('\n', ' ') + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (Exception ignored) {}
    }
}
