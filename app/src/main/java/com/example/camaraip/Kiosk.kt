package com.example.camaraip

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.edit

/**
 * Modo kiosco con el Lock Task Mode de Android. Solo funciona si la app es Device Owner:
 *
 *   adb shell dpm set-device-owner com.example.camaraip/.AdminReceiver
 *
 * Con el kiosco activo:
 *  - la app es la pantalla de inicio, así que arranca sola al prender la tablet;
 *  - se bloquean Inicio, Recientes, la barra de notificaciones y el menú de apagado;
 *  - no aparece la pantalla de bloqueo (si no hay PIN/patrón configurado).
 *
 * Sin Device Owner todo esto se ignora y la app funciona normalmente.
 */
class Kiosk(private val activity: Activity) {

    private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    private val activityManager = activity.getSystemService(ActivityManager::class.java)
    private val admin = ComponentName(activity, AdminReceiver::class.java)
    private val prefs = activity.getSharedPreferences("config", Context.MODE_PRIVATE)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(activity.packageName)

    /** Se puede apagar desde el menú de administración sin dejar de ser Device Owner. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        private set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Como Device Owner, la app se otorga el permiso de cámara sin mostrar el diálogo. */
    fun grantCameraPermission() {
        if (!isDeviceOwner) return
        dpm.setPermissionGrantState(
            admin, activity.packageName, Manifest.permission.CAMERA,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
        )
    }

    /** Llamar desde onResume: entra en modo kiosco si corresponde. */
    fun lockIfEnabled() {
        if (!isDeviceOwner || !isEnabled || isLocked()) return
        try {
            applyPolicies(kiosk = true)
            activity.startLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo activar el modo kiosco", e)
        }
    }

    fun enable() {
        isEnabled = true
        lockIfEnabled()
    }

    fun disable() {
        isEnabled = false
        if (isLocked()) activity.stopLockTask()
        if (isDeviceOwner) applyPolicies(kiosk = false)
    }

    /** Deja de ser Device Owner, para poder desinstalar la app sin resetear la tablet. */
    @Suppress("DEPRECATION") // Sigue siendo la única forma de hacerlo desde la propia app.
    fun removeDeviceOwner() {
        disable()
        dpm.clearDeviceOwnerApp(activity.packageName)
    }

    private fun isLocked() = activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun applyPolicies(kiosk: Boolean) {
        val packageName = activity.packageName
        dpm.setLockTaskPackages(admin, if (kiosk) arrayOf(packageName) else emptyArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Sin barra de estado, sin botones Inicio/Recientes y sin menú de apagado.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        dpm.setKeyguardDisabled(admin, kiosk)

        // Pantalla de inicio fija: al encender la tablet se abre directamente esta app.
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        if (kiosk) {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
        }
    }

    private companion object {
        const val TAG = "Kiosk"
        const val KEY_ENABLED = "kiosk_enabled"
    }
}
