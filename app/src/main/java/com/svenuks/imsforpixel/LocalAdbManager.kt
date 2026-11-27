package com.svenuks.imsforpixel

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File

object LocalAdbManager {
    private const val TAG = "LocalAdbManager"

    fun initCertStore(context: Context) {
        try {
            val privateKeyFile = File(context.filesDir, "kadb_private_key.pem")
            val store = OkioFilePrivateKeyStore(privateKeyFile.absolutePath.toPath())
            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure KadbCert", e)
        }
    }

    suspend fun pair(context: Context, port: Int, code: String): Result<Unit> {
        return try {
            Kadb.pair("127.0.0.1", port, code, context.filesDir.absolutePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun checkAuthorized(port: Int): Boolean {
        return try {
            Kadb.create("127.0.0.1", port, 3000, 3000).use { kadb ->
                kadb.shell("echo 1").exitCode == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun pollImsQueryTool(context: Context, kadb: Kadb) {
        val pathRes = kadb.shell("pm path ${context.packageName}")
        if (pathRes.exitCode != 0) return
        val path = pathRes.output.trim().substringAfter("package:")
        if (path.isEmpty()) return

        val queryCmd = "export CLASSPATH=$path; app_process /system/bin com.svenuks.imsforpixel.ImsQueryTool"
        val queryRes = kadb.shell(queryCmd)
        if (queryRes.exitCode != 0) return

        for (line in queryRes.output.lines()) {
            if (!line.startsWith("STATUS:")) continue
            val parts = line.split(":")
            if (parts.size < 9) continue

            val slot = parts[1].toIntOrNull() ?: continue
            val subId = parts[2].toIntOrNull() ?: -1
            val carrierName = parts[3]
            val isEmbedded = parts[4].toBoolean()
            val mccMnc = parts[5]
            val imsRegistered = parts[6].toBoolean()
            val parsedTransport = parts[7]
            val crossSim = parts[8].toBoolean()
            val wfcRaw = parts.getOrNull(9)

            val existing = SimConfigStore.loadLiveStatus(context, slot)
            val wfc = when (wfcRaw) {
                "true" -> true
                "false" -> false
                else -> existing.wfcEnabled
            }
            val transport = when {
                !imsRegistered -> "NONE"
                parsedTransport == "REGISTERED" && existing.imsRegistered &&
                    existing.imsTransport in listOf("CROSS_SIM", "IWLAN", "NR", "LTE") -> existing.imsTransport
                else -> parsedTransport
            }
            val updated = existing.copy(
                subId = if (subId > 0) subId else existing.subId,
                carrierName = carrierName.ifBlank { existing.carrierName },
                isEmbedded = isEmbedded,
                mccMnc = mccMnc.ifBlank { existing.mccMnc },
                imsRegistered = imsRegistered,
                imsTransport = transport,
                crossSimEnabled = crossSim,
                wfcEnabled = wfc,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
            SimConfigStore.saveLiveStatus(context, updated)
        }
    }

    fun runInstrumentation(context: Context, port: Int, action: String, targetSlot: Int): Result<String> {
        val prefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            Kadb.create("127.0.0.1", port, 60000, 60000).use { kadb ->
                val cmd = "am instrument -w -e action $action -e target_slot $targetSlot ${context.packageName}/com.svenuks.imsforpixel.BrokerInstrumentation"
                val response = kadb.shell(cmd)
                val logBody = "$ $cmd\nexit=${response.exitCode}\n${response.output}"
                SimConfigStore.setLastExecutionLog(prefs, logBody)
                if (response.exitCode == 0) {
                    if (action == "apply") {
                        SimConfigStore.setNeedsReapplyAfterBoot(prefs, false, commit = true)
                        if (SimConfigStore.isAutoDisableWifiDebug(prefs)) {
                            try {
                                kadb.shell("settings put global adb_wifi_enabled 0")
                            } catch (_: Exception) {
                            }
                        }
                    }
                    requestTileUpdate(context)
                    Result.success(response.output)
                } else {
                    Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                }
            }
        } catch (e: Exception) {
            SimConfigStore.setLastExecutionLog(prefs, "action=$action slot=$targetSlot failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun requestTileUpdate(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, TensorImsTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
