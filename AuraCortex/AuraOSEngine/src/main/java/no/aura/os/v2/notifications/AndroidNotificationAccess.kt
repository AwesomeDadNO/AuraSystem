package no.aura.os.v2.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AndroidNotificationAccess(private val context: Context) {
    fun settingsIntent(): Intent? = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).takeIf { it.resolveActivity(context.packageManager) != null }
    fun state(): SystemAccess {
        if (settingsIntent() == null) return SystemAccess.UNAVAILABLE
        val granted = if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, AuraNotificationListener::class.java))
            else context.packageName in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        return if (!granted) SystemAccess.NOT_GRANTED else if (AuraNotificationListener.connected) SystemAccess.CONNECTED else SystemAccess.DISCONNECTED
    }
    /** POST permission is independent of notification access and in-app use. This does not request it. */
    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Host explicitly attaches only its active visible profile. No history is read and no background profile is guessed. */
object SystemNotificationSession {
    @Volatile private var target: Pair<NotificationCenter, String>? = null
    @Synchronized fun attach(center: NotificationCenter, profile: String) { detach(); target = center to profile }
    @Synchronized fun detach() { target?.let { (center, profile) -> center.setSystemAccess(profile, SystemAccess.DISCONNECTED) }; target = null }
    @Synchronized fun capability(access: SystemAccess) { target?.let { (center, profile) -> center.setSystemAccess(profile, access) } }
    @Synchronized fun accept(event: CenterEvent) { target?.let { (center, profile) -> center.ingest(profile, event) } }
}
class AuraNotificationListener : NotificationListenerService() {
    companion object { @Volatile var connected: Boolean = false; private set }
    override fun onListenerConnected() { connected = true; SystemNotificationSession.capability(SystemAccess.CONNECTED) }
    override fun onListenerDisconnected() { connected = false; SystemNotificationSession.capability(SystemAccess.REVOKED) }
    override fun onDestroy() { connected = false; SystemNotificationSession.capability(SystemAccess.DISCONNECTED); super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!connected || sbn == null || sbn.packageName == packageName) return
        val n = sbn.notification
        // Never copy private, secret, ongoing or unrenderable content into a local profile.
        if (n.visibility != Notification.VISIBILITY_PUBLIC || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
        SystemNotificationSession.accept(CenterEvent("android:${sbn.key}:${sbn.postTime}", NotificationCategory.SYSTEM,
            sbn.packageName, title, n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), sbn.postTime))
    }
}
