package dev.mlbb.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import java.net.NetworkInterface as JavaInterface

/**
 * Встроенный VPN-клиент на ядре sing-box: сам подключается к серверу из
 * подписки, а через Clash API ядра оверлей видит соединения игры.
 */
class BoxVpnService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "BoxVpn"
        private const val CHANNEL_ID = "box"
        private const val NOTIF_ID = 3
        private const val ACTION_STOP = "dev.mlbb.overlay.BOX_STOP"

        @Volatile var isRunning = false
            private set
        @Volatile var isStarting = false
            private set
        @Volatile var lastError: String? = null
        @Volatile var ports: BoxConfig.Ports? = null
            private set

        private var setupDone = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BoxVpnService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BoxVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var box: BoxService? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread { stopBox(); stopSelf() }.start()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!isRunning && !isStarting) {
            isStarting = true
            Thread { startBox() }.start()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 3, Intent(this, BoxVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MLBB Server — VPN")
            .setContentText("Подключено, оверлей следит за сервером игры")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun freePort(): Int = ServerSocket().use {
        it.bind(InetSocketAddress("127.0.0.1", 0))
        it.localPort
    }

    private fun startBox() {
        try {
            lastError = null
            VpnLog.clear()
            AppSettings.load(this)
            Subscription.load(this)
            val nodes = Subscription.usable(this)
            if (nodes.isEmpty()) throw RuntimeException("Нет серверов: добавь ссылку подписки")
            // Серверы XHTTP — через ядро Xray, каждому свой локальный порт
            val xrayPorts = if (XrayCore.available(this)) {
                nodes.filter { it.xrayLink != null }.associate { it.tag to freePort() }
            } else emptyMap()
            XrayCore.start(this, nodes.filter { it.tag in xrayPorts }.associate { it.tag to (it.xrayLink!! to xrayPorts.getValue(it.tag)) })

            if (!setupDone) {
                val work = File(filesDir, "box").apply { mkdirs() }
                Libbox.setup(SetupOptions().apply {
                    basePath = filesDir.path
                    workingPath = work.path
                    tempPath = cacheDir.path
                })
                // Паники ядра (Go) пишутся в stderr — сохраняем, чтобы показать при следующем запуске
                try {
                    Libbox.redirectStderr(File(filesDir, CrashReport.GO).path)
                } catch (_: Exception) {
                }
                setupDone = true
            }

            val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val p = BoxConfig.Ports(freePort(), secret, freePort())
            // Выбранный сервер должен попасть в конфиг (XHTTP без Xray туда не попадает)
            val usable = nodes.filter { it.xrayLink == null || it.tag in xrayPorts }
            if (usable.isEmpty()) throw RuntimeException("Нет серверов, которые поддерживает это устройство")
            val selected = AppSettings.selectedTag.takeIf { t -> usable.any { it.tag == t } } ?: usable.first().tag
            val config = BoxConfig.build(
                nodes, selected, p, packageName,
                onlyGame = AppSettings.onlyGame, battleDirect = AppSettings.battleDirect,
                gameMode = AppSettings.gameMode,
                xrayPorts = xrayPorts,
            )
            val service = Libbox.newService(config, this)
            service.start()
            box = service
            ports = p
            isRunning = true
            // Оверлей и лог: читаем соединения игры у своего же ядра
            if (AppSettings.gameMode) MonitorService.start(this, p.api, p.secret, p.mixed)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            val tail = VpnLog.tail(8)
            lastError = "Не удалось подключить VPN: ${e.message}" + if (tail.isNotEmpty()) "\n\nЖурнал ядра:\n$tail" else ""
            stopBox()
            stopSelf()
        } finally {
            isStarting = false
        }
    }

    private fun stopBox() {
        isRunning = false
        ports = null
        XrayCore.stop()
        MonitorService.stop(this)
        try {
            box?.close()
        } catch (_: Exception) {
        }
        box = null
        try {
            tunPfd?.close()
        } catch (_: Exception) {
        }
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        Thread { stopBox(); stopSelf() }.start()
    }

    override fun onDestroy() {
        if (isRunning) stopBox()
        super.onDestroy()
    }

    // ------------------------- PlatformInterface -------------------------

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) throw Exception("protect($fd) failed")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw Exception("нет разрешения на VPN")
        val b = Builder().setSession("MLBB Server").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) v4.next().let { b.addAddress(it.address(), it.prefix()) }
        var hasV6 = false
        val v6 = options.inet6Address
        while (v6.hasNext()) v6.next().let { b.addAddress(it.address(), it.prefix()); hasV6 = true }

        if (options.autoRoute) {
            b.addDnsServer(options.dnsServerAddress.value)
            if (!addRoutes(b, options.inet4RouteAddress)) b.addRoute("0.0.0.0", 0)
            if (hasV6 && !addRoutes(b, options.inet6RouteAddress)) b.addRoute("::", 0)

            val include = options.includePackage
            while (include.hasNext()) {
                try { b.addAllowedApplication(include.next()) } catch (_: PackageManager.NameNotFoundException) {}
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                try { b.addDisallowedApplication(exclude.next()) } catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        val pfd = b.establish() ?: throw Exception("Android не дал поднять VPN")
        tunPfd = pfd
        return pfd.fd
    }

    private fun addRoutes(b: Builder, it: RoutePrefixIterator): Boolean {
        var any = false
        while (it.hasNext()) {
            val r = it.next()
            b.addRoute(r.address(), r.prefix())
            any = true
        }
        return any
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun useProcFS() = Build.VERSION.SDK_INT < 29

    override fun findConnectionOwner(ipProtocol: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return cm.getConnectionOwnerUid(
            ipProtocol, InetSocketAddress(srcIp, srcPort), InetSocketAddress(dstIp, dstPort)
        )
    }

    override fun packageNameByUid(uid: Int): String =
        packageManager.getPackagesForUid(uid)?.firstOrNull() ?: throw Exception("нет пакета для uid $uid")

    override fun uidByPackageName(packageName: String): Int =
        packageManager.getPackageUid(packageName, 0)

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Ядру сообщаем только о реальной смене сети (интерфейса): каждое такое сообщение
            // может сбросить соединения, а мобильная сеть дёргает свойства постоянно.
            private var lastKey: String? = null
            private var current: Network? = null
            private val main = android.os.Handler(android.os.Looper.getMainLooper())

            private fun update(network: Network?) {
                val lp: LinkProperties? = network?.let { cm.getLinkProperties(it) }
                val name = lp?.interfaceName
                if (name == null) {
                    if (lastKey != "") {
                        lastKey = ""
                        listener.updateDefaultInterface("", -1, false, false)
                    }
                    return
                }
                val idx = try { JavaInterface.getByName(name)?.index ?: -1 } catch (_: Exception) { -1 }
                val key = "$name/$idx"
                if (key == lastKey) return
                lastKey = key
                val caps = cm.getNetworkCapabilities(network)
                val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                listener.updateDefaultInterface(name, idx, expensive, false)
            }

            override fun onAvailable(network: Network) {
                current = network
                update(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                current = network
                update(network)
            }
            override fun onLost(network: Network) {
                if (current == network) current = null
                // При переключении сетей новая обычно приходит следом — не дёргаем ядро зря
                main.postDelayed({ if (current == null) update(null) }, 1500)
            }
        }
        networkCallback = cb
        // Важно: не «сеть по умолчанию» — после подъёма VPN ею для нас станет сам туннель,
        // ядро привяжется к нему и уйдёт в петлю. Берём лучшую сеть БЕЗ VPN, как официальный клиент.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (Build.VERSION.SDK_INT >= 31) {
            cm.registerBestMatchingNetworkCallback(request, cb, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            cm.requestNetwork(request, cb)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = ArrayList<BoxInterface>()
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = lp.interfaceName ?: continue
            val ji = try { JavaInterface.getByName(name) } catch (_: Exception) { null } ?: continue
            val bi = BoxInterface()
            bi.name = name
            bi.index = ji.index
            bi.mtu = try { ji.mtu } catch (_: Exception) { 1500 }
            bi.addresses = StrIter(lp.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" })
            bi.dnsServer = StrIter(lp.dnsServers.mapNotNull { it.hostAddress })
            var flags = 0
            if (ji.isUp) flags = flags or 1
            if (ji.isLoopback) flags = flags or 8
            if (ji.isPointToPoint) flags = flags or 16
            if (ji.supportsMulticast()) flags = flags or 4096
            bi.flags = flags
            bi.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            bi.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            list.add(bi)
        }
        val it = list.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = it.hasNext()
            override fun next(): BoxInterface = it.next()
        }
    }

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState = WIFIState("", "")
    override fun clearDNSCache() {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
    override fun writeLog(message: String) {
        Log.i("sing-box", message)
        VpnLog.add(message)
    }

    private class StrIter(items: List<String>) : StringIterator {
        private val it = items.iterator()
        private val size = items.size
        override fun hasNext() = it.hasNext()
        override fun next(): String = it.next()
        override fun len() = size
    }
}
