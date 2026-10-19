package com.brother.netgate.noroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * 免Root本地VPN防火墙：
 * 被限制App的流量进TUN后全部丢弃（=断网），
 * 其余App用 addDisallowedApplication 从VPN豁免，直连不受影响。
 */
class FirewallVpn : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val main = Handler(Looper.getMainLooper())
    private val rebuildTask = Runnable { establish() }
    private lateinit var cm: ConnectivityManager
    private var lastType = ""

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = schedule()
        override fun onLost(n: Network) = schedule()
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
            if (curType() != lastType) schedule()
        }
    }

    private val pkgCb = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = schedule()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        alive = true
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), netCb)
        registerReceiver(pkgCb, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        notifyForeground()
        establish()
        return START_STICKY
    }

    /** 网络/应用变化后防抖重建 */
    private fun schedule() {
        main.removeCallbacks(rebuildTask)
        main.postDelayed(rebuildTask, 500)
    }

    /** 扫全部活跃网络判定当前主用类型（VPN在场时底层网络仍在allNetworks里） */
    private fun curType(): String {
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi"
        }
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "data"
        }
        return "other"
    }

    /** 按当前网络类型取要拦截的包名集合 */
    private fun blockedNow(type: String): Set<String> {
        val key = if (type == "wifi") "wifi|" else "data|"
        return getSharedPreferences("netgate", MODE_PRIVATE).all
            .filter { it.key.startsWith(key) && it.value == true }
            .map { it.key.substring(key.length) }
            .toSet()
    }

    /** 重建VPN隧道：只让"被拦App"的流量进来，进来的一律丢弃 */
    private fun establish() {
        val type = curType()
        lastType = type
        val blocked = blockedNow(type)
        val pkgs = packageManager.getInstalledApplications(0).map { it.packageName }
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        if (blocked.isEmpty()) return          // 无限制不建隧道，不显示VPN图标
        try {
            val b = Builder().setSession("NetGate")
                .addAddress("10.20.30.40", 24).addRoute("0.0.0.0/0")
                .addAddress("fd00:2030:40::1", 126).addRoute("::/0")
            for (p in pkgs) {
                if (p !in blocked && p != packageName) b.addDisallowedApplication(p)
            }
            b.addDisallowedApplication(packageName)
            tun = b.establish()
        } catch (e: Exception) {
            return
        }
        val fd = tun ?: return
        // 读循环：只读不写 = 全部丢弃，被拦App完全无网络
        Thread {
            try {
                val fis = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(65536)
                while (fis.read(buf) > 0) { /* drop */ }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun notifyForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ng", "网络闸门", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ng")
            .setContentTitle("网络闸门运行中")
            .setContentText("本地VPN按App拦截，免Root")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, n)
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        instance = null
        main.removeCallbacks(rebuildTask)
        try { cm.unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        try { unregisterReceiver(pkgCb) } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: FirewallVpn? = null
        @Volatile var alive = false

        /** 让运行中的服务按最新配置重建规则 */
        fun ping(ctx: Context) {
            if (alive) ctx.startService(Intent(ctx, FirewallVpn::class.java))
        }
    }
}