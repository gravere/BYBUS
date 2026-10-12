package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;


/** Non-exported host-process bridge. Never reads or forwards incoming extras/URIs. */
public final class LimbusPlayStoreActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent details = new Intent("com.example.limbuszhcn.SHOW_GAME_UPDATES")
                    .setClassName(getPackageName(), "com.example.limbuszhcn.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(details);
                Log.i("LimbusVA", "Opened host game update channels");
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(this, "请返回 BYBUS 打开游戏更新", Toast.LENGTH_LONG).show();
                Log.w("LimbusVA", "No host game update handler");
            }
        } finally {
            finish();
        }
    }
}
