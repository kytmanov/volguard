package com.local.volguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal status screen. Setup is normally done over adb, so this only exists so
 * the app has a launcher entry and a shortcut to re-enable accessibility.
 */
public class MainActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setText("VolGuard auto-dismisses Sony's \"Check volume level\" dialog "
                + "and restores Walkman master volume to your pre-clamp level.\n\n"
                + "It accepts the safe-volume gate immediately, restores volume via "
                + "Sony's volume service, then taps OK once the button appears.\n\n"
                + "If it ever stops working, enable VolGuard below under Accessibility.");
        root.addView(tv);

        Button btn = new Button(this);
        btn.setText("Open Accessibility settings");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(btn);

        setContentView(root);
    }
}
