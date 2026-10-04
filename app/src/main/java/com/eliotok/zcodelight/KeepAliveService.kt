package com.eliotok.zcodelight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台保活服务：官方远程页面跑在 WebView 里，浏览器式的后台挂起是掉线根因；
 * 本服务用「前台服务 + WakeLock + WiFi 锁 + 网络恢复自动重载 + 心跳探测」托底，
 * 让页面在息屏、切后台、网络抖动之后仍然活着并自动恢复。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1
        private const val PROBE_INTERVAL_MS = 30_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hadNetwork = true
    private var offlineStreak = 0

    private val watchdog = object : Runnable {
        override fun run() {
            probe()
            handler.postDelayed(this, PROBE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground("保活服务已启动")
        acquireLocks()
        registerNetworkCallback()
        handler.post(watchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(StateBus.status.value)
        acquireLocks()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- 锁 ----------

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zcode-light:keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "zcode-light:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    // ---------- 网络监听：断网恢复后自动重载页面 ----------

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!hadNetwork) {
                    hadNetwork = true
                    setStatus("网络恢复，正在重载页面")
                    handler.postDelayed({
                        WebViewHolder.webView?.let { wv ->
                            handler.post {
                                wv.reload()
                                WebViewHolder.lastLoaded = wv.url
                            }
                        }
                    }, 1_500L)
                }
            }

            override fun onLost(network: Network) {
                hadNetwork = hasUsableNetwork()
                if (!hadNetwork) setStatus("网络已断开，等待恢复")
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    private fun hasUsableNetwork(): Boolean {
        val manager = connectivityManager ?: return false
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    // ---------- 心跳探测：页面报告自己是否在线 ----------

    private fun probe() {
        val webView = WebViewHolder.webView ?: run {
            setStatus("等待页面创建")
            return
        }
        handler.post {
            webView.evaluateJavascript("(function(){return navigator.onLine ? 'on' : 'off';})()") { result ->
                if (result != null && result.contains("off")) {
                    offlineStreak++
                    if (offlineStreak >= 2) setStatus("网络离线，等待恢复")
                } else {
                    offlineStreak = 0
                    setStatus("已连接 · 保活中")
                }
            }
        }
    }

    // ---------- 通知 ----------

    private fun setStatus(text: String) {
        if (StateBus.status.value != text) {
            StateBus.status.value = text
            updateNotification(text)
        }
    }

    private fun startInForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "连接保活", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), foregroundType())
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZCode 远程保活中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
}
