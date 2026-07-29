package com.local.volguard;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so VolGuard has a component to name in
 * {@code MediaSessionManager.getActiveSessions()}, which refuses callers that are
 * neither an enabled notification listener nor holders of MEDIA_CONTENT_CONTROL
 * (a signature|privileged permission a sideloaded app cannot get).
 *
 * Nothing is overridden on purpose. Granting notification access hands VolGuard the
 * ability to read every notification on the device, which is a lot to ask for a volume
 * tool; the least it can do is not use it. Only the session list is ever read, and only
 * to answer "is something playing right now" — see VolGuardService.isPlaybackActive().
 */
public class VolGuardNotificationListener extends NotificationListenerService {
}
