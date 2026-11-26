package com.shzxm.carwithingateway

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnShowGuide: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTeslaUrl: TextView
    private lateinit var tvUpstream: TextView

    // Step 1: Cloudflare API, custom domain & DDNS
    private lateinit var etCfToken: EditText
    private lateinit var btnLoadDomains: Button
    private lateinit var etDomain: EditText
    private lateinit var etHijackIp: EditText
    private lateinit var etListenPort: EditText
    private lateinit var cbAutoDdns: CheckBox
    private lateinit var btnSyncDdns: Button
    private lateinit var tvDdnsStatus: TextView

    // Step 2: HTTPS certificate
    private lateinit var rgTlsMode: RadioGroup
    private lateinit var rbCloudflare: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var layoutCloudflare: LinearLayout
    private lateinit var layoutManualCert: LinearLayout
    private lateinit var etAcmeEmail: EditText
    private lateinit var btnIssueCert: Button
    private lateinit var btnImportCert: Button
    private lateinit var btnImportKey: Button
    private lateinit var tvCertStatus: TextView

    // Step 3: Upstream iPhone LAN target
    private lateinit var btnScanDevices: Button
    private lateinit var etTargetPort: EditText
    private lateinit var etTargetMac: EditText
    private lateinit var etManualUpstream: EditText

    // Step 4: Start / Stop & logs
    private lateinit var btnToggle: Button
    private lateinit var btnCopyUrl: Button
    private lateinit var btnClearLogs: Button
    private lateinit var tvLogs: TextView

    private val pickCertLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyUriToFile(uri, "manual_fullchain.pem", "证书 fullchain.pem")
    }

    private val pickKeyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyUriToFile(uri, "manual_privkey.pem", "私钥 privkey.pem")
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadConfigToUi()
        refreshCertStatus()
        requestNotificationPermissionIfNeeded()
        setupListeners()
        observeGatewayState()
    }

    private fun bindViews() {
        btnShowGuide = findViewById(R.id.btnShowGuide)
        tvStatus = findViewById(R.id.tvStatus)
        tvTeslaUrl = findViewById(R.id.tvTeslaUrl)
        tvUpstream = findViewById(R.id.tvUpstream)

        etCfToken = findViewById(R.id.etCfToken)
        btnLoadDomains = findViewById(R.id.btnLoadDomains)
        etDomain = findViewById(R.id.etDomain)
        etHijackIp = findViewById(R.id.etHijackIp)
        etListenPort = findViewById(R.id.etListenPort)
        cbAutoDdns = findViewById(R.id.cbAutoDdns)
        btnSyncDdns = findViewById(R.id.btnSyncDdns)
        tvDdnsStatus = findViewById(R.id.tvDdnsStatus)

        rgTlsMode = findViewById(R.id.rgTlsMode)
        rbCloudflare = findViewById(R.id.rbCloudflare)
        rbManual = findViewById(R.id.rbManual)
        layoutCloudflare = findViewById(R.id.layoutCloudflare)
        layoutManualCert = findViewById(R.id.layoutManualCert)
        etAcmeEmail = findViewById(R.id.etAcmeEmail)
        btnIssueCert = findViewById(R.id.btnIssueCert)
        btnImportCert = findViewById(R.id.btnImportCert)
        btnImportKey = findViewById(R.id.btnImportKey)
        tvCertStatus = findViewById(R.id.tvCertStatus)

        btnScanDevices = findViewById(R.id.btnScanDevices)
        etTargetPort = findViewById(R.id.etTargetPort)
        etTargetMac = findViewById(R.id.etTargetMac)
        etManualUpstream = findViewById(R.id.etManualUpstream)

        btnToggle = findViewById(R.id.btnToggle)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogs.movementMethod = ScrollingMovementMethod()
    }

    private fun setupListeners() {
        btnShowGuide.setOnClickListener { showTutorialDialog() }

        rgTlsMode.setOnCheckedChangeListener { _, checkedId ->
            val isManual = checkedId == R.id.rbManual
            layoutCloudflare.visibility = if (isManual) View.GONE else View.VISIBLE
            layoutManualCert.visibility = if (isManual) View.VISIBLE else View.GONE
            refreshCertStatus()
        }

        // Step 1: Load Cloudflare zones and build custom domain
        btnLoadDomains.setOnClickListener {
            val cfg = readConfigFromUi()
            if (cfg.cloudflareApiToken.isBlank()) {
                Toast.makeText(this, "请先填写 Cloudflare API Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GatewayConfig.save(this, cfg)
            btnLoadDomains.isEnabled = false
            btnLoadDomains.text = "正在读取 Cloudflare 域名..."

            lifecycleScope.launch {
                val res = CoreRunner.runAction(this@MainActivity, cfg, "list-zones", streamToLog = false)
                btnLoadDomains.isEnabled = true
                btnLoadDomains.text = "读取我的 Cloudflare 域名并选择"

                if (res.exitCode != 0) {
                    GatewayLog.append("[cf] 读取域名失败: ${res.output}")
                    Toast.makeText(this@MainActivity, "读取失败，请检查 Token 权限 (Zone:Read)", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val zones = CoreRunner.parseZoneNames(res.output)
                if (zones.isEmpty()) {
                    Toast.makeText(this@MainActivity, "未读取到可用域名，请确认 Token 有 Zone:Read 权限", Toast.LENGTH_LONG).show()
                    return@launch
                }
                GatewayLog.append("[cf] 读取到 ${zones.size} 个域名: ${zones.joinToString(", ")}")
                showZonePickerDialog(zones)
            }
        }

        // Step 1: One-click Cloudflare DDNS sync
        btnSyncDdns.setOnClickListener {
            val cfg = readConfigFromUi()
            if (cfg.cloudflareApiToken.isBlank() || cfg.domain.isBlank()) {
                Toast.makeText(this, "请先填写 Cloudflare API Token 和自定义域名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GatewayConfig.save(this, cfg)
            updateTeslaUrlPreview()
            btnSyncDdns.isEnabled = false
            btnSyncDdns.text = "正在同步 DDNS..."
            tvDdnsStatus.text = "DDNS 状态：正在写入 ${cfg.domain} -> ${cfg.hijackIp}..."
            tvDdnsStatus.setTextColor(0xFFF59E0B.toInt())

            lifecycleScope.launch {
                val res = CoreRunner.runAction(this@MainActivity, cfg, "ddns", streamToLog = true)
                btnSyncDdns.isEnabled = true
                btnSyncDdns.text = "一键同步 Cloudflare DDNS（写入 A 记录）"
                if (res.exitCode == 0) {
                    tvDdnsStatus.text = "DDNS 状态：已同步 ${cfg.domain} -> ${cfg.hijackIp}（仅 DNS，已关闭小黄云）"
                    tvDdnsStatus.setTextColor(0xFF10B981.toInt())
                    Toast.makeText(this@MainActivity, "DDNS 同步成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvDdnsStatus.text = "DDNS 状态：同步失败，请查看下方日志"
                    tvDdnsStatus.setTextColor(0xFFEF4444.toInt())
                    Toast.makeText(this@MainActivity, "DDNS 同步失败", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Step 2: One-click Let's Encrypt certificate issue / check
        btnIssueCert.setOnClickListener {
            val cfg = readConfigFromUi()
            if (cfg.cloudflareApiToken.isBlank() || cfg.domain.isBlank()) {
                Toast.makeText(this, "请先在第 1 步填写 Cloudflare API Token 和自定义域名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GatewayConfig.save(this, cfg)
            updateTeslaUrlPreview()
            btnIssueCert.isEnabled = false
            btnIssueCert.text = "正在申请 / 验证证书（约 20 秒）..."
            tvCertStatus.text = "证书状态：正在通过 Cloudflare DNS-01 申请 Let's Encrypt 证书..."
            tvCertStatus.setTextColor(0xFFF59E0B.toInt())

            lifecycleScope.launch {
                val res = CoreRunner.runAction(this@MainActivity, cfg, "cert", streamToLog = true)
                btnIssueCert.isEnabled = true
                btnIssueCert.text = "一键申请 / 检测 Let's Encrypt 证书"
                if (res.exitCode == 0) {
                    val summaryLine = res.output.lineSequence().lastOrNull { it.contains("certificate ready") } ?: "证书有效"
                    tvCertStatus.text = "证书状态：已就绪（$summaryLine）"
                    tvCertStatus.setTextColor(0xFF10B981.toInt())
                    Toast.makeText(this@MainActivity, "HTTPS 证书已就绪", Toast.LENGTH_SHORT).show()
                } else {
                    tvCertStatus.text = "证书状态：申请失败，请查看下方日志"
                    tvCertStatus.setTextColor(0xFFEF4444.toInt())
                    Toast.makeText(this@MainActivity, "证书申请失败，请检查日志", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnImportCert.setOnClickListener { pickCertLauncher.launch(arrayOf("*/*")) }
        btnImportKey.setOnClickListener { pickKeyLauncher.launch(arrayOf("*/*")) }

        // Step 3: Scan hotspot LAN devices to bind iPhone MAC or IP
        btnScanDevices.setOnClickListener {
            val cfg = readConfigFromUi()
            btnScanDevices.isEnabled = false
            btnScanDevices.text = "正在扫描热点内网设备..."

            lifecycleScope.launch {
                val neighbors = UpstreamDiscovery.scanNeighbors(cfg.targetPort)
                btnScanDevices.isEnabled = true
                btnScanDevices.text = "扫描当前热点在线设备（点选绑定主 iPhone）"

                if (neighbors.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "未扫描到热点内网设备，请确认：1) 已授 Root 权限；2) 本机已开热点且主 iPhone 已连上",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                showDevicePickerDialog(neighbors, cfg.targetPort)
            }
        }

        // Step 4: Start / Stop gateway
        btnToggle.setOnClickListener {
            if (GatewayLog.stateFlow.value.running) {
                val stopIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                val cfg = readConfigFromUi()
                if (cfg.domain.isBlank()) {
                    Toast.makeText(this, "请先在第 1 步填写自定义域名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (cfg.tlsMode == "cloudflare" && cfg.cloudflareApiToken.isBlank()) {
                    Toast.makeText(this, "请填写 Cloudflare API Token，或在第 2 步切换为手动导入证书", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                GatewayConfig.save(this, cfg)
                updateTeslaUrlPreview()

                val startIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_START
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }

        btnCopyUrl.setOnClickListener {
            val url = currentTeslaUrl()
            copyText("Tesla CarWithin URL", url)
            Toast.makeText(this, "已复制车机地址: $url", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener { GatewayLog.clear() }
    }

    private fun showZonePickerDialog(zones: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("选择你的 Cloudflare 根域名")
            .setItems(zones.toTypedArray()) { _, which ->
                val selectedZone = zones[which]
                showSubdomainInputDialog(selectedZone)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSubdomainInputDialog(rootZone: String) {
        val input = EditText(this).apply {
            hint = "子域名前缀（留空则用根域名），例如 car"
            setText("car")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("设置自定义域名前缀")
            .setMessage("已选根域名：$rootZone\n请输入想要使用的子域名前缀（如 car 将生成 car.$rootZone）：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val prefix = input.text.toString().trim().trimEnd('.')
                val fullDomain = if (prefix.isEmpty() || prefix == "@") rootZone else "$prefix.$rootZone"
                etDomain.setText(fullDomain)
                GatewayConfig.save(this, readConfigFromUi())
                updateTeslaUrlPreview()
                Toast.makeText(this, "已设置自定义域名：$fullDomain", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDevicePickerDialog(neighbors: List<NeighborEntry>, targetPort: Int) {
        val labels = neighbors.map { entry ->
            val badge = if (entry.portOpen) "★ 已发现 CarWithin ($targetPort)" else "在线设备"
            val macStr = entry.mac.ifBlank { "未知 MAC" }
            "${entry.ip}  [$badge]\nMAC: $macStr"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择主 iPhone（热点内网设备）")
            .setItems(labels) { _, which ->
                val picked = neighbors[which]
                val actions = arrayOf(
                    "绑定 MAC：${picked.mac.ifBlank { "无" }}（推荐，iPhone IP 变了自动追踪）",
                    "临时固定内网 IP：${picked.ip}（不自动追踪）"
                )
                AlertDialog.Builder(this)
                    .setTitle("如何绑定 ${picked.ip}？")
                    .setItems(actions) { _, mode ->
                        if (mode == 0 && picked.mac.isNotBlank()) {
                            etTargetMac.setText(picked.mac)
                            etManualUpstream.setText("")
                            Toast.makeText(this, "已绑定 MAC ${picked.mac}，IP 变动将自动追踪", Toast.LENGTH_SHORT).show()
                        } else {
                            etManualUpstream.setText(picked.ip)
                            Toast.makeText(this, "已填入内网目标 IP：${picked.ip}", Toast.LENGTH_SHORT).show()
                        }
                        GatewayConfig.save(this, readConfigFromUi())
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTutorialDialog() {
        val guideText = """
            【架构原理】
            Root 安卓开 5 GHz 热点，主 iPhone（CarWithin）与特斯拉都连该热点。
            本 App 将你的自定义域名通过 Cloudflare DDNS 指向 100.64.0.1（非 RFC1918 内网段，特斯拉浏览器放行），并用 Root iptables 劫持 100.64.0.1:8443 到本机 HTTPS 反代，再转发给主 iPhone 的 9888 端口。视频流 100% 走车内 Wi-Fi 局域网，0 公网视频流量！

            【完整操作顺序】
            ▶ 第 0 步：准备 Cloudflare API Token
            1. 登录 dash.cloudflare.com → 右上角头像 → My Profile → API Tokens → Create Token。
            2. 选择「Edit zone DNS」模板（或自定义权限：Zone:Zone:Read + Zone:DNS:Edit）。
            3. 复制生成的 Token。

            ▶ 第 1 步：填写 Token、选择域名并同步 DDNS
            1. 把 Token 粘贴到 App 第 1 步，点击「读取我的 Cloudflare 域名并选择」，输入前缀（如 car）生成自定义域名。
            2. 保持 DDNS IP 为 100.64.0.1、端口 8443。
            3. 点击「一键同步 Cloudflare DDNS」，自动写入 A 记录（关闭小黄云）。

            ▶ 第 2 步：一键申请正规 HTTPS 证书
            点击「一键申请 / 检测 Let's Encrypt 证书」，等待约 20 秒看到绿色「已就绪」提示即可。首次申请后本地保存，以后离线也能用。

            ▶ 第 3 步：设置主 iPhone 内网地址（防 IP 变动）
            1. 开启安卓 5 GHz 个人热点，让主 iPhone 连上热点并打开 CarWithin（9888 端口）。
            2. 点击「扫描当前热点在线设备」，选中标有 ★ CarWithin 的主 iPhone，选择「绑定 MAC」。
            3. 之后即使主 iPhone IP 变动，网关也会每 4 秒自动追踪新 IP 并热更新。

            ▶ 主 iPhone 用 Surge / Loon 必看放行规则
            为防止内网 9888 端口被代理劫持，把安卓热点网段（通常是 192.168.43.0/24）加入：
            • Surge：skip-proxy 与 tun-excluded-routes
            • Loon：skip-proxy 与 bypass-tun

            ▶ 第 4 步：启动网关 & 特斯拉打开
            1. 点击「启动本地网关」（首次请允许 Root 授权）。
            2. 特斯拉连上安卓 5 GHz 热点，浏览器打开复制好的 https://你的域名:8443 即可享受绿锁低延迟 CarPlay！
            3. 主 iPhone 蓝牙连接特斯拉负责出声音。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("使用教程（按顺序操作）")
            .setMessage(guideText)
            .setPositiveButton("我知道了", null)
            .setNeutralButton("复制 Surge/Loon 规则") { _, _ ->
                val rule = "skip-proxy = 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local\n" +
                    "# Surge 请添加:\ntun-excluded-routes = 192.168.43.0/24, 192.168.0.0/16\n" +
                    "# Loon 请添加:\nbypass-tun = 192.168.43.0/24, 192.168.0.0/16"
                copyText("Surge/Loon Bypass Rules", rule)
                Toast.makeText(this, "已复制 Surge/Loon 放行规则", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun observeGatewayState() {
        lifecycleScope.launch {
            GatewayLog.stateFlow.collect { state ->
                tvStatus.text = "状态：${state.statusText}"
                tvStatus.setTextColor(if (state.running) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())
                tvUpstream.text = "当前主 iPhone 上游：${state.currentUpstream}"
                btnToggle.text = if (state.running) "停止本地网关" else "启动本地网关"
            }
        }

        lifecycleScope.launch {
            GatewayLog.logsFlow.collect { logText ->
                tvLogs.text = logText
            }
        }
    }

    private fun loadConfigToUi() {
        val cfg = GatewayConfig.load(this)
        etCfToken.setText(cfg.cloudflareApiToken)
        etDomain.setText(cfg.domain)
        etHijackIp.setText(cfg.hijackIp)
        etListenPort.setText(cfg.listenPort.toString())
        cbAutoDdns.isChecked = cfg.autoDdns
        etAcmeEmail.setText(cfg.acmeEmail)
        etTargetPort.setText(cfg.targetPort.toString())
        etTargetMac.setText(cfg.targetMac)
        etManualUpstream.setText(cfg.manualUpstreamIp)

        if (cfg.tlsMode == "manual") {
            rbManual.isChecked = true
            layoutCloudflare.visibility = View.GONE
            layoutManualCert.visibility = View.VISIBLE
        } else {
            rbCloudflare.isChecked = true
            layoutCloudflare.visibility = View.VISIBLE
            layoutManualCert.visibility = View.GONE
        }
        updateTeslaUrlPreview()
    }

    private fun readConfigFromUi(): GatewayConfig {
        return GatewayConfig(
            domain = etDomain.text.toString().trim(),
            hijackIp = etHijackIp.text.toString().trim().ifEmpty { "100.64.0.1" },
            autoDdns = cbAutoDdns.isChecked,
            listenPort = etListenPort.text.toString().trim().toIntOrNull() ?: 8443,
            tlsMode = if (rbManual.isChecked) "manual" else "cloudflare",
            cloudflareApiToken = etCfToken.text.toString().trim(),
            acmeEmail = etAcmeEmail.text.toString().trim(),
            targetPort = etTargetPort.text.toString().trim().toIntOrNull() ?: 9888,
            targetMac = etTargetMac.text.toString().trim(),
            manualUpstreamIp = etManualUpstream.text.toString().trim(),
        )
    }

    private fun currentTeslaUrl(): String {
        val domain = etDomain.text.toString().trim().ifEmpty { "car.yourdomain.com" }
        val port = etListenPort.text.toString().trim().toIntOrNull() ?: 8443
        return "https://$domain:$port"
    }

    private fun updateTeslaUrlPreview() {
        tvTeslaUrl.text = "特斯拉车机访问地址：${currentTeslaUrl()}"
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun copyUriToFile(uri: Uri, targetName: String, label: String) {
        try {
            val outFile = File(filesDir, targetName)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            Toast.makeText(this, "已导入 $label", Toast.LENGTH_SHORT).show()
            refreshCertStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshCertStatus() {
        if (rbManual.isChecked) {
            val hasCert = File(filesDir, "manual_fullchain.pem").exists()
            val hasKey = File(filesDir, "manual_privkey.pem").exists()
            tvCertStatus.text = "证书状态：fullchain.pem (${if (hasCert) "已导入" else "未导入"}) / privkey.pem (${if (hasKey) "已导入" else "未导入"})"
            tvCertStatus.setTextColor(if (hasCert && hasKey) 0xFF10B981.toInt() else 0xFF9CA3AF.toInt())
        } else {
            val autoCert = File(File(filesDir, "gateway_data"), "fullchain.pem")
            val autoKey = File(File(filesDir, "gateway_data"), "privkey.pem")
            if (autoCert.exists() && autoKey.exists()) {
                tvCertStatus.text = "证书状态：本地已缓存 Let's Encrypt 证书（可点击上方按钮检测/续期）"
                tvCertStatus.setTextColor(0xFF10B981.toInt())
            } else {
                tvCertStatus.text = "证书状态：尚未申请（请点击上方按钮一键申请）"
                tvCertStatus.setTextColor(0xFF9CA3AF.toInt())
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
