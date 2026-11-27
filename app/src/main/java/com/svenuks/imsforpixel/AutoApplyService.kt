package com.svenuks.imsforpixel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class AutoApplyService : Service() {

    companion object {
        private const val TAG = "ModernImsAutoApply"
        private const val CHANNEL_ID = "auto_apply_channel"
        private const val NOTIF_ID = 205
        private const val TIMEOUT_MS = 90_000L
        const val EXTRA_KNOWN_PORT = "extra_known_port"

        fun startWatcher(context: Context, knownPort: Int = -1) {
            val intent = Intent(context, AutoApplyService::class.java).apply {
                if (knownPort in 1..65535) putExtra(EXTRA_KNOWN_PORT, knownPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val isHandlingPort = AtomicBoolean(false)
    private val isFinished = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val stopRunnable = Runnable {
        if (!isFinished.getAndSet(true)) {
            Log.d(TAG, "AutoApply watcher timed out after 90s")
            postOutcomeNotification(
                getString(R.string.auto_apply_timeout_title),
                getString(R.string.auto_apply_timeout_body)
            )
            stopSelfCleanly()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LocalAdbManager.initCertStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsShortForeground()

        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (!SimConfigStore.hasAnyOverrideEnabled(prefs)) {
            postOutcomeNotification(
                getString(R.string.auto_apply_no_sim_title),
                getString(R.string.auto_apply_no_sim_body)
            )
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, TIMEOUT_MS)

        val knownPort = intent?.getIntExtra(EXTRA_KNOWN_PORT, -1) ?: -1
        if (knownPort in 1..65535) {
            tryApplyOnPort(knownPort)
        }
        startNsdDiscovery()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        if (!isFinished.getAndSet(true)) {
            stopSelfCleanly()
        }
    }

    private fun startAsShortForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_auto_apply),
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(ch)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(getString(R.string.auto_apply_watching_title))
            .setContentText(getString(R.string.auto_apply_watching_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startNsdDiscovery() {
        stopNsdDiscovery()
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (isFinished.get()) return
                if (serviceInfo.serviceType.contains("adb-tls-connect")) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            if (port in 1..65535) {
                                tryApplyOnPort(port)
                            }
                        }
                    })
                }
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start NSD discovery", e)
        }
    }

    private fun tryApplyOnPort(port: Int) {
        if (isFinished.get() || !isHandlingPort.compareAndSet(false, true)) return
        Thread {
            try {
                if (LocalAdbManager.checkAuthorized(port)) {
                    val result = LocalAdbManager.runInstrumentation(this, port, "apply", -1)
                    if (result.isSuccess) {
                        isFinished.set(true)
                        handler.removeCallbacks(stopRunnable)
                        MainActivity.onInstrumentDone?.invoke("apply", true)
                        postStatusSuccess()
                        stopSelfCleanly()
                        return@Thread
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-apply attempt on port $port failed", e)
            } finally {
                isHandlingPort.set(false)
            }
        }.start()
    }

    private fun postStatusSuccess() {
        val s0 = SimConfigStore.loadLiveStatus(this, 0)
        val s1 = SimConfigStore.loadLiveStatus(this, 1)
        val summary = listOf(s0, s1)
            .filter { it.subId != -1 || it.imsRegistered || it.configApplied }
            .joinToString(" | ") { st ->
                val name = st.carrierName.ifBlank { "SIM ${st.slotIndex + 1}" }
                val reg = if (st.imsRegistered) "IMS (${st.imsTransport})" else "Not Reg"
                val backup = if (st.crossSimEnabled) "+Backup" else ""
                "$name: $reg$backup"
            }.ifBlank { getString(R.string.notification_title_volte_success) }

        postOutcomeNotification(getString(R.string.notification_title_volte_success), summary)
    }

    private fun postOutcomeNotification(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val statusChannel = "ims_status_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    statusChannel,
                    getString(R.string.notification_channel_ims_status),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 310, launchIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, statusChannel)
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(203, notification)
    }

    private fun stopNsdDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
    }

    private fun stopSelfCleanly() {
        handler.removeCallbacks(stopRunnable)
        stopNsdDiscovery()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopRunnable)
        stopNsdDiscovery()
    }
}
