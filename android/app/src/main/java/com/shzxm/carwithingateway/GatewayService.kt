package com.shzxm.carwithingateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyProcess: Process? = null
    private var discoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeConfig: GatewayConfig? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val cfg = GatewayConfig.load(this)
                activeConfig = cfg
                startForeground(NOTIFICATION_ID, buildNotification("正在启动网关...", ""))
                acquireWakeLock()
                scope.launch { startGateway(cfg) }
            }
        }
        return START_STICKY
    }

    private suspend fun startGateway(cfg: GatewayConfig) {
        GatewayLog.updateState(running = true, statusText = "正在检查 Root 与配置...", currentUpstream = "探测中...")
        GatewayLog.append("========== 启动 CarWithin Gateway ==========")

        if (!RootIptables.hasRoot()) {
            GatewayLog.append("[错误] 未获取到 Root 权限，请在 Magisk / KernelSU 中授权！")
            GatewayLog.updateState(running = false, statusText = "启动失败：无 Root 权限")
            stopSelf()
            return
        }

        val iptablesResult = RootIptables.applyRules(cfg.hijackIp, cfg.listenPort)
        if (iptablesResult.isFailure) {
            GatewayLog.append("[错误] ${iptablesResult.exceptionOrNull()?.message}")
            GatewayLog.updateState(running = false, statusText = "启动失败：iptables 规则错误")
            stopSelf()
            return
        }

        val configFile = cfg.writeCoreConfigFile(this)

        val coreBin = File(applicationInfo.nativeLibraryDir, "libcarwithin_proxy.so")
        if (!coreBin.exists()) {
            GatewayLog.append("[错误] 找不到内置反代核心: ${coreBin.absolutePath}")
            RootIptables.removeRules(cfg.hijackIp, cfg.listenPort)
            GatewayLog.updateState(running = false, statusText = "启动失败：缺少核心二进制")
            stopSelf()
            return
        }

        GatewayLog.append("[core] 启动反代核心 (${coreBin.name})...")
        try {
            val pb = ProcessBuilder(coreBin.absolutePath, "-config", configFile.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proxyProcess = proc

            scope.launch {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        GatewayLog.append(line!!)
                    }
                }
                val code = proc.waitFor()
                if (GatewayLog.stateFlow.value.running) {
                    GatewayLog.append("[core] 核心进程异常退出 (exitCode=$code)")
                    RootIptables.removeRules(cfg.hijackIp, cfg.listenPort)
                    GatewayLog.updateState(running = false, statusText = "已停止 (核心退出 code=$code)")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            GatewayLog.append("[错误] 启动核心失败: ${e.message}")
            RootIptables.removeRules(cfg.hijackIp, cfg.listenPort)
            GatewayLog.updateState(running = false, statusText = "启动失败")
            stopSelf()
            return
        }

        GatewayLog.updateState(
            running = true,
            statusText = "运行中 (${cfg.hijackIp}:${cfg.listenPort} -> :${cfg.listenPort})",
            currentUpstream = if (cfg.manualUpstreamIp.isNotBlank()) "${cfg.manualUpstreamIp}:${cfg.targetPort}" else "等待发现主 iPhone..."
        )
        updateNotification("运行中 · ${cfg.domain}:${cfg.listenPort}", "正在监听本地 5GHz 热点")

        startUpstreamDiscoveryLoop(cfg)
    }

    private fun startUpstreamDiscoveryLoop(cfg: GatewayConfig) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            var lastPushed = ""
            while (isActive) {
                val target = if (cfg.manualUpstreamIp.isNotBlank()) {
                    "http://" + cfg.manualUpstreamIp.trim() + ":" + cfg.targetPort
                } else {
                    UpstreamDiscovery.discover(cfg.targetPort, cfg.targetMac) ?: ""
                }

                if (target.isNotEmpty() && target != lastPushed) {
                    if (pushUpstreamToCore(cfg.controlPort, target)) {
                        lastPushed = target
                        GatewayLog.append("[discovery] 已绑定主 iPhone 上游 -> $target")
                        GatewayLog.updateState(currentUpstream = target)
                        updateNotification("已连接主 iPhone ($target)", "车机访问 ${cfg.domain}:${cfg.listenPort}")
                    }
                }
                delay(4000)
            }
        }
    }

    private fun pushUpstreamToCore(controlPort: Int, upstream: String): Boolean {
        return try {
            val url = URL("http", "127.0.0.1", controlPort, "/upstream")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().put("upstream", upstream).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun stopGateway() {
        GatewayLog.updateState(running = false, statusText = "已停止", currentUpstream = "未探测")
        discoveryJob?.cancel()
        discoveryJob = null

        proxyProcess?.let {
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }
        proxyProcess = null

        activeConfig?.let {
            RootIptables.removeRules(it.hijackIp, it.listenPort)
        }
        releaseWakeLock()
        GatewayLog.append("========== 网关已停止 ==========")
    }

    override fun onDestroy() {
        stopGateway()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarWithinGateway::WakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(title: String, text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarWithin Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    companion object {
        const val ACTION_START = "com.shzxm.carwithingateway.START"
        const val ACTION_STOP = "com.shzxm.carwithingateway.STOP"
        private const val CHANNEL_ID = "carwithin_gateway_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
