package com.distressedelk.lumi;

import android.content.Intent;
import android.service.quicksettings.TileService;

public class LumiTileService extends TileService {
    @Override public void onClick() {
        super.onClick();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(MainActivity.EXTRA_AUTO_LISTEN, true);
        startActivityAndCollapse(i);
    }
}
