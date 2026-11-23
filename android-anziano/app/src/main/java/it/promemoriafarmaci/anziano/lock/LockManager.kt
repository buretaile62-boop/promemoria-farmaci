package it.promemoriafarmaci.anziano.lock

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager

/**
 * Blocco del telefono durante il promemoria del farmaco ("lock task mode").
 *
 * Funziona senza richieste di conferma solo se l'app è proprietaria del dispositivo
 * (configurazione una tantum, vedi README). Anche in blocco restano sempre disponibili:
 * il telefono (chiamate al tutore e in arrivo), il 112 e il menu di spegnimento/emergenza.
 */
object LockManager {
    private val KNOWN_DIALERS = listOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
    )

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun configure(context: Context) {
        if (!isDeviceOwner(context)) return
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val packages = (listOfNotNull(context.packageName, dialer) + KNOWN_DIALERS).distinct().toTypedArray()
        dpm(context).setLockTaskPackages(admin(context), packages)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm(context).setLockTaskFeatures(
                admin(context),
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
            )
        }
    }

    fun canLock(context: Context) = dpm(context).isLockTaskPermitted(context.packageName)

    fun lock(activity: Activity) {
        if (canLock(activity) && !isLocked(activity)) runCatching { activity.startLockTask() }
    }

    fun unlock(activity: Activity) {
        if (isLocked(activity)) runCatching { activity.stopLockTask() }
    }

    fun isLocked(context: Context) =
        context.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
}
