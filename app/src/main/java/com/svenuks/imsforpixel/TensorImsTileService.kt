package com.svenuks.imsforpixel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class TensorImsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val needsReapply = SimConfigStore.isNeedsReapplyAfterBoot(prefs)
        val s0 = SimConfigStore.loadLiveStatus(this, 0)
        val s1 = SimConfigStore.loadLiveStatus(this, 1)
        val anyApplied = (s0.configApplied || s1.configApplied) && !needsReapply

        tile.state = if (anyApplied) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activeSummary = listOf(s0, s1)
                .filter { it.configApplied }
                .joinToString(" · ") { st ->
                    val name = st.carrierName.ifBlank { "SIM ${st.slotIndex + 1}" }
                    val transport = when (st.imsTransport) {
                        "IWLAN" -> "VoWiFi"
                        "CROSS_SIM" -> "Backup"
                        else -> st.imsTransport
                    }
                    "$name $transport"
                }
            tile.subtitle = when {
                needsReapply -> getString(R.string.tile_subtitle_reapply)
                activeSummary.isNotBlank() -> activeSummary
                SimConfigStore.hasAnyOverrideEnabled(prefs) -> getString(R.string.tile_subtitle_ready)
                else -> getString(R.string.tile_subtitle_setup)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (!SimConfigStore.hasAnyOverrideEnabled(prefs)) {
            openActivitySafely(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            return
        }

        // Start 90s Wireless ADB watcher so toggling Wireless Debugging ON auto-applies immediately
        AutoApplyService.startWatcher(this)

        val settingsIntent = try {
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        openActivitySafely(settingsIntent)
    }

    private fun openActivitySafely(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                401,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
