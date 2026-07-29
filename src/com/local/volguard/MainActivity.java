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
        render();
    }

    /** Rebuilt on resume so the permission status is not stale after a trip to Settings. */
    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
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
                + "The dialog also takes audio focus and stops the music, so VolGuard "
                + "starts playback again — usually within about a second.\n\n"
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

        TextView notif = new TextView(this);
        notif.setPadding(0, pad, 0, 0);
        notif.setText(hasNotificationAccess()
                ? "Notification access: granted.\n\n"
                        + "Idle suppression is fully active. While the screen is off and "
                        + "nothing is playing, VolGuard holds the volume down so Sony's "
                        + "exposure timer stops, and restores it the moment you press play."
                : "Notification access: not granted.\n\n"
                        + "Without it VolGuard cannot tell playing from paused on this "
                        + "device — Sony's player is invisible to every other playback API "
                        + "— so idle suppression stays conservative and will rarely engage. "
                        + "Everything else still works.\n\n"
                        + "VolGuard reads no notifications. The permission exists only "
                        + "because it is the only way to read media playback state.");
        root.addView(notif);

        Button notifBtn = new Button(this);
        notifBtn.setText("Open Notification access settings");
        notifBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(
                        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        root.addView(notifBtn);

        setContentView(root);
    }

    /** Same check the platform makes before letting us list media sessions. */
    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }
}
