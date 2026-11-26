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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTeslaUrl: TextView
    private lateinit var tvUpstream: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopyUrl: Button
    private lateinit var etDomain: EditText
    private lateinit var etHijackIp: EditText
    private lateinit var etListenPort: EditText
    private lateinit var rgTlsMode: RadioGroup
    private lateinit var rbCloudflare: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var layoutCloudflare: LinearLayout
    private lateinit var layoutManualCert: LinearLayout
    private lateinit var etCfToken: EditText
    private lateinit var etAcmeEmail: EditText
    private lateinit var btnImportCert: Button
    private lateinit var btnImportKey: Button
    private lateinit var tvCertStatus: TextView
    private lateinit var etTargetPort: EditText
    private lateinit var etTargetMac: EditText
    private lateinit var etManualUpstream: EditText
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
        refreshManualCertStatus()
        requestNotificationPermissionIfNeeded()

        rgTlsMode.setOnCheckedChangeListener { _, checkedId ->
            val isManual = checkedId == R.id.rbManual
            layoutCloudflare.visibility = if (isManual) View.GONE else View.VISIBLE
            layoutManualCert.visibility = if (isManual) View.VISIBLE else View.GONE
        }

        btnImportCert.setOnClickListener { pickCertLauncher.launch(arrayOf("*/*")) }
        btnImportKey.setOnClickListener { pickKeyLauncher.launch(arrayOf("*/*")) }
        btnClearLogs.setOnClickListener { GatewayLog.clear() }

        btnCopyUrl.setOnClickListener {
            val url = currentTeslaUrl()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Tesla CarWithin URL", url))
            Toast.makeText(this, "已复制: $url", Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            if (GatewayLog.stateFlow.value.running) {
                val stopIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                val cfg = readConfigFromUi()
                if (cfg.domain.isBlank()) {
                    Toast.makeText(this, "请先填写域名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (cfg.tlsMode == "cloudflare" && cfg.cloudflareApiToken.isBlank()) {
                    Toast.makeText(this, "请填写 Cloudflare API Token，或切换为手动导入证书", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                GatewayConfig.save(this, cfg)
                tvTeslaUrl.text = "特斯拉车机地址：${currentTeslaUrl()}"

                val startIntent = Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_START
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }

        lifecycleScope.launch {
            GatewayLog.stateFlow.collect { state ->
                tvStatus.text = "状态：${state.statusText}"
                tvStatus.setTextColor(if (state.running) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())
                tvUpstream.text = "当前上游 (主 iPhone)：${state.currentUpstream}"
                btnToggle.text = if (state.running) "停止网关" else "启动网关"
            }
        }

        lifecycleScope.launch {
            GatewayLog.logsFlow.collect { logText ->
                tvLogs.text = logText
            }
        }
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTeslaUrl = findViewById(R.id.tvTeslaUrl)
        tvUpstream = findViewById(R.id.tvUpstream)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        etDomain = findViewById(R.id.etDomain)
        etHijackIp = findViewById(R.id.etHijackIp)
        etListenPort = findViewById(R.id.etListenPort)
        rgTlsMode = findViewById(R.id.rgTlsMode)
        rbCloudflare = findViewById(R.id.rbCloudflare)
        rbManual = findViewById(R.id.rbManual)
        layoutCloudflare = findViewById(R.id.layoutCloudflare)
        layoutManualCert = findViewById(R.id.layoutManualCert)
        etCfToken = findViewById(R.id.etCfToken)
        etAcmeEmail = findViewById(R.id.etAcmeEmail)
        btnImportCert = findViewById(R.id.btnImportCert)
        btnImportKey = findViewById(R.id.btnImportKey)
        tvCertStatus = findViewById(R.id.tvCertStatus)
        etTargetPort = findViewById(R.id.etTargetPort)
        etTargetMac = findViewById(R.id.etTargetMac)
        etManualUpstream = findViewById(R.id.etManualUpstream)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogs.movementMethod = ScrollingMovementMethod()
    }

    private fun loadConfigToUi() {
        val cfg = GatewayConfig.load(this)
        etDomain.setText(cfg.domain)
        etHijackIp.setText(cfg.hijackIp)
        etListenPort.setText(cfg.listenPort.toString())
        etCfToken.setText(cfg.cloudflareApiToken)
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
        tvTeslaUrl.text = "特斯拉车机地址：${currentTeslaUrl()}"
    }

    private fun readConfigFromUi(): GatewayConfig {
        return GatewayConfig(
            domain = etDomain.text.toString().trim(),
            hijackIp = etHijackIp.text.toString().trim().ifEmpty { "100.64.0.1" },
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

    private fun copyUriToFile(uri: Uri, targetName: String, label: String) {
        try {
            val outFile = File(filesDir, targetName)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            Toast.makeText(this, "已导入 $label", Toast.LENGTH_SHORT).show()
            refreshManualCertStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshManualCertStatus() {
        val hasCert = File(filesDir, "manual_fullchain.pem").exists()
        val hasKey = File(filesDir, "manual_privkey.pem").exists()
        tvCertStatus.text = "证书状态：fullchain.pem (${if (hasCert) "已导入" else "未导入"}) / privkey.pem (${if (hasKey) "已导入" else "未导入"})"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
