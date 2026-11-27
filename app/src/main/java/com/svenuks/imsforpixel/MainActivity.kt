package com.svenuks.imsforpixel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.Kadb
import com.svenuks.imsforpixel.ui.DashboardScreen
import com.svenuks.imsforpixel.ui.DiagnosticsScreen
import com.svenuks.imsforpixel.ui.SimConfigScreen
import com.svenuks.imsforpixel.ui.theme.ImsForPixelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        @JvmStatic
        var pairingPort: Int? = null
        var onAuthStatusChanged: (() -> Unit)? = null
        var onInstrumentDone: ((action: String, success: Boolean) -> Unit)? = null
    }

    private val actionPair: String
        get() = "$packageName.ACTION_PAIR"

    private var pairingReceiver: BroadcastReceiver? = null
    private var showPairingOnPermissionGrant = false

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (showPairingOnPermissionGrant) {
                showPairingNotification()
            }
        } else if (showPairingOnPermissionGrant) {
            Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
        }
        showPairingOnPermissionGrant = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Exception) {
            Log.e("ImsForPixelPlus", "Failed to apply HiddenApiBypass exemptions", e)
        }

        LocalAdbManager.initCertStore(this)
        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        SimConfigStore.ensureDefaults(prefs)

        val filter = IntentFilter(actionPair)
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == actionPair) {
                    val remoteInput = RemoteInput.getResultsFromIntent(intent)
                    val code = remoteInput?.getCharSequence("extra_pairing_code")?.toString()?.trim()
                    if (!code.isNullOrEmpty()) {
                        handleNotificationPairing(code)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            showPairingOnPermissionGrant = false
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ImsForPixelTheme {
                val recheckSignal = remember { mutableStateOf(System.currentTimeMillis()) }
                MainAppShell(recheckSignal = recheckSignal)
            }
        }
    }

    fun requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                showPairingOnPermissionGrant = true
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                showPairingNotification()
            }
        } else {
            showPairingNotification()
        }
    }

    fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(ch)
        }
        val s0 = SimConfigStore.loadLiveStatus(this, 0)
        val s1 = SimConfigStore.loadLiveStatus(this, 1)
        val anyRegistered = s0.imsRegistered || s1.imsRegistered
        val title = if (isActivate) {
            if (anyRegistered) getString(R.string.notification_title_volte_success) else getString(R.string.notification_title_volte_done)
        } else {
            getString(R.string.notification_title_config_restored)
        }
        val body = if (isActivate) {
            listOf(s0, s1)
                .filter { it.subId != -1 || it.imsRegistered || it.configApplied }
                .joinToString(" | ") { status ->
                    val name = status.carrierName.ifBlank { "SIM ${status.slotIndex + 1}" }
                    val state = if (status.imsRegistered) "IMS (${status.imsTransport})" else "Not Reg"
                    val backup = if (status.crossSimEnabled) "+Backup" else ""
                    "$name: $state$backup"
                }.ifBlank { getString(R.string.notification_config_cleared) }
        } else {
            getString(R.string.notification_config_cleared)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(203, notification)
    }

    private fun createPairingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pairing_channel",
                getString(R.string.notification_channel_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_pairing_desc)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        createPairingChannel()
        val remoteInput = RemoteInput.Builder("extra_pairing_code")
            .setLabel(getString(R.string.notification_pairing_reply_label))
            .build()
        val intent = Intent(actionPair).apply { `package` = packageName }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.notification_pairing_send_action),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(getString(R.string.notification_pairing_prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(action)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(202, notification)
    }

    private fun showPairingStatusNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(202, notification)
    }

    private fun handleNotificationPairing(code: String) {
        val port = pairingPort
        if (port == null) {
            Toast.makeText(this, getString(R.string.toast_pairing_port_missing), Toast.LENGTH_LONG).show()
            showPairingStatusNotification(getString(R.string.notification_pairing_failed_port))
            return
        }

        Toast.makeText(this, getString(R.string.toast_pairing_in_progress), Toast.LENGTH_SHORT).show()
        showPairingStatusNotification(getString(R.string.notification_pairing_port_progress, port))

        CoroutineScope(Dispatchers.IO).launch {
            val result = LocalAdbManager.pair(this@MainActivity, port, code)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_success), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_success))
                        onAuthStatusChanged?.invoke()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_failed, error.message ?: ""))
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        getSystemService(NotificationManager::class.java)?.cancel(202)
    }
}

private enum class AppDestination {
    HOME, CONFIGURE, DIAGNOSTICS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(recheckSignal: MutableState<Long>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(AppDestination.HOME) }
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isAuthorized by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    val isInstrumenting = remember { AtomicBoolean(false) }

    var status0 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 0)) }
    var status1 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 1)) }

    // Periodic status file reload + callbacks
    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = {
            recheckSignal.value = System.currentTimeMillis()
        }
        MainActivity.onInstrumentDone = { action, _ ->
            if (action == "apply" || action == "clear") {
                (context as? MainActivity)?.showImsStatusNotification(action == "apply")
            }
            recheckSignal.value = System.currentTimeMillis()
        }
        while (true) {
            delay(1000)
            recheckSignal.value = System.currentTimeMillis()
        }
    }

    LaunchedEffect(recheckSignal.value) {
        status0 = SimConfigStore.loadLiveStatus(context, 0)
        status1 = SimConfigStore.loadLiveStatus(context, 1)
    }

    // Track Wi-Fi status
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isWifiConnected = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            override fun onLost(network: Network) {
                isWifiConnected = false
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try { cm.registerNetworkCallback(req, callback) } catch (_: Exception) {}
        isWifiConnected = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        onDispose {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    // mDNS Wireless Debugging discovery
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    DisposableEffect(Unit) {
        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("adb-tls-connect")) {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            portInput = resolvedServiceInfo.port.toString()
                        }
                    })
                }
            }
        }
        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("adb-tls-pairing")) {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            MainActivity.pairingPort = resolvedServiceInfo.port
                        }
                    })
                }
            }
        }
        try { nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener) } catch (_: Exception) {}
        try { nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener) } catch (_: Exception) {}
        onDispose {
            try { nsdManager.stopServiceDiscovery(connectListener) } catch (_: Exception) {}
            try { nsdManager.stopServiceDiscovery(pairingListener) } catch (_: Exception) {}
        }
    }

    // Background Kadb authorization check + IMS polling
    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            isAuthorized = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            isAuthorized = LocalAdbManager.checkAuthorized(port)
            var activeKadb: Kadb? = null
            try {
                while (true) {
                    if (isInstrumenting.get()) {
                        delay(1000)
                        continue
                    }
                    try {
                        val kadb = activeKadb ?: Kadb.create("127.0.0.1", port, 5000, 5000).also { activeKadb = it }
                        isAuthorized = true
                        LocalAdbManager.pollImsQueryTool(context, kadb)
                    } catch (_: Exception) {
                        isAuthorized = false
                        try { activeKadb?.close() } catch (_: Exception) {}
                        activeKadb = null
                    }
                    delay(3000)
                }
            } finally {
                try { activeKadb?.close() } catch (_: Exception) {}
            }
        }
    }

    fun runAction(action: String, targetSlot: Int) {
        val port = portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, context.getString(R.string.toast_enable_wireless_debugging), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (action == "apply" && targetSlot == -1) {
            val s0Enabled = SimConfigStore.loadSimConfig(prefs, 0).overrideEnabled
            val s1Enabled = SimConfigStore.loadSimConfig(prefs, 1).overrideEnabled
            if (!s0Enabled && !s1Enabled) {
                Toast.makeText(context, context.getString(R.string.toast_no_sim_override_enabled), Toast.LENGTH_LONG).show()
                return
            }
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LocalAdbManager.runInstrumentation(context, port, action, targetSlot)
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    if (action == "fix_apn") {
                        Toast.makeText(context, context.getString(R.string.toast_apn_fixed), Toast.LENGTH_SHORT).show()
                    }
                    MainActivity.onInstrumentDone?.invoke(action, true)
                },
                onFailure = { error ->
                    val resId = if (action == "clear") R.string.toast_restore_failed else R.string.toast_activation_failed
                    Toast.makeText(context, context.getString(resId, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                actions = {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { runAction("query", -1) }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh_status))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppDestination.HOME,
                    onClick = { currentTab = AppDestination.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = currentTab == AppDestination.CONFIGURE,
                    onClick = { currentTab = AppDestination.CONFIGURE },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_configure)) }
                )
                NavigationBarItem(
                    selected = currentTab == AppDestination.DIAGNOSTICS,
                    onClick = { currentTab = AppDestination.DIAGNOSTICS },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_diagnostics)) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppDestination.HOME -> DashboardScreen(
                    status0 = status0,
                    status1 = status1,
                    isWifiConnected = isWifiConnected,
                    isAuthorized = isAuthorized,
                    portInput = portInput,
                    onPortInputChange = { portInput = it },
                    isApplying = isApplying,
                    onConfigureSlot = { slot ->
                        selectedSimSlot = slot
                        currentTab = AppDestination.CONFIGURE
                    },
                    onApplySlot = { slot -> runAction("apply", slot) },
                    onRestoreSlot = { slot -> runAction("clear", slot) },
                    onFixApnSlot = { slot -> runAction("fix_apn", slot) },
                    onApplyAllEnabled = { runAction("apply", -1) },
                    onRestoreAll = { runAction("clear", -1) },
                    onRefreshStatus = { runAction("query", -1) }
                )
                AppDestination.CONFIGURE -> SimConfigScreen(
                    selectedSlot = selectedSimSlot,
                    onSlotSelected = { selectedSimSlot = it },
                    status0 = status0,
                    status1 = status1,
                    isApplying = isApplying,
                    recheckSignal = recheckSignal.value,
                    onRefreshStatus = { runAction("query", -1) },
                    onApplySlot = { slot -> runAction("apply", slot) },
                    onRestoreSlot = { slot -> runAction("clear", slot) }
                )
                AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                    status0 = status0,
                    status1 = status1,
                    portInput = portInput,
                    onPortInputChange = { portInput = it },
                    isApplying = isApplying,
                    recheckSignal = recheckSignal.value,
                    onRefreshStatus = { runAction("query", -1) },
                    onFixApnSlot = { slot -> runAction("fix_apn", slot) }
                )
            }
        }
    }
}
