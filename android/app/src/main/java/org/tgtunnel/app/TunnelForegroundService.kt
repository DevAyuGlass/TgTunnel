package org.tgtunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal const val ACTION_START_TUNNEL = "org.tgtunnel.app.action.START"
internal const val ACTION_STOP_TUNNEL = "org.tgtunnel.app.action.STOP"
private const val CHANNEL_ID = "tgtunnel_service"
private const val NOTIFICATION_ID = 9443
private const val SERVICE_PREFS = "tgtunnel_service_state"
private const val KEY_WAS_RUNNING = "was_running"

internal fun startTunnelService(context: Context, settings: AppSettings) {
    val intent = Intent(context, TunnelForegroundService::class.java).apply {
        action = ACTION_START_TUNNEL
        putExtra("port", settings.port.toIntOrNull() ?: 1443)
        putExtra("secret", settings.secret)
        putExtra("worker", settings.workerDomain)
        putExtra("cf_proxy", settings.cfProxyFallback)
        putExtra("dc_ip", settings.dcIp)
        putExtra("tls_hello_split", settings.tlsClientHelloSplit)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

internal fun stopTunnelService(context: Context) {
    context.startService(Intent(context, TunnelForegroundService::class.java).setAction(ACTION_STOP_TUNNEL))
}

class TunnelForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val starting = AtomicBoolean(false)
    @Volatile private var stopping = false
    private val notificationLock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск прокси…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TUNNEL) {
            stopping = true
            getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WAS_RUNNING, false).apply()
            NativeBridge.stop()
            TunnelEvents.emit("SERVICE_STATE:STOPPED · TGTunnel остановлен")
            removeForegroundNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        if (starting.compareAndSet(false, true)) {
            val prefs = getSharedPreferences("tgtunnel_settings", MODE_PRIVATE)
            val port = intent?.getIntExtra("port", prefs.getInt("local_port", 1443)) ?: prefs.getInt("local_port", 1443)
            val secret = intent?.getStringExtra("secret") ?: (prefs.getString("mtproto_secret", "") ?: "")
            val worker = intent?.getStringExtra("worker") ?: (prefs.getString("cloudflare_worker_domain", "") ?: "")
            val cfProxy = intent?.getBooleanExtra("cf_proxy", prefs.getBoolean("cloudflare_proxy_fallback", true))
                ?: prefs.getBoolean("cloudflare_proxy_fallback", true)
            val dcIp = intent?.getStringExtra("dc_ip") ?: (prefs.getString("dc_ip_redirects", "2:149.154.167.220,4:149.154.167.220") ?: "")
            val tlsHelloSplit = intent?.getBooleanExtra("tls_hello_split", prefs.getBoolean("tls_clienthello_split", false))
                ?: prefs.getBoolean("tls_clienthello_split", false)
            getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WAS_RUNNING, true).apply()
            executor.execute {
                val result = if (NativeBridge.isRunning()) "OK:Proxy already running" else
                    NativeBridge.start(port, secret, worker, cfProxy, dcIp, tlsHelloSplit) { message ->
                        TunnelEvents.emit(message)
                        updateNotification("Работает · 127.0.0.1:" + port)
                    }
                starting.set(false)
                if (result.startsWith("OK:")) {
                    updateNotification("Работает · 127.0.0.1:" + port)
                    TunnelEvents.emit("SERVICE_STATE:CONNECTED · Фоновый сервис активен")
                    TunnelEvents.emit(result.removePrefix("OK:"))
                } else {
                    getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WAS_RUNNING, false).apply()
                    TunnelEvents.emit("SERVICE_STATE:ERROR · Ошибка запуска: " + result.removePrefix("ERR:"))
                    stopping = true
                    removeForegroundNotification()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the explicitly started proxy alive after the activity task is dismissed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        removeForegroundNotification()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "TGTunnel", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Состояние фонового MTProto-прокси"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TGTunnel")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(status: String) {
        synchronized(notificationLock) {
            if (stopping) return
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun removeForegroundNotification() {
        synchronized(notificationLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
