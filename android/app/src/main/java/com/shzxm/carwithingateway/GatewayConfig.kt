package com.shzxm.carwithingateway

import android.content.Context
import org.json.JSONObject
import java.io.File

data class GatewayConfig(
    val domain: String = "car.example.com",
    val hijackIp: String = "100.64.0.1",
    val autoDdns: Boolean = true,
    val listenPort: Int = 8443,
    val controlPort: Int = 18443,
    val tlsMode: String = "cloudflare", // "cloudflare" or "manual"
    val cloudflareApiToken: String = "",
    val acmeEmail: String = "",
    val targetPort: Int = 9888,
    val targetMac: String = "",
    val manualUpstreamIp: String = "",
) {
    fun toCoreJson(dataDir: String, certFile: String, keyFile: String): String {
        val initialUpstream = if (manualUpstreamIp.isNotBlank()) {
            "http://${manualUpstreamIp.trim()}:$targetPort"
        } else {
            ""
        }
        return JSONObject().apply {
            put("domain", domain.trim())
            put("ddns_ip", hijackIp.trim().ifEmpty { "100.64.0.1" })
            put("auto_ddns", autoDdns)
            put("listen_port", listenPort)
            put("control_port", controlPort)
            put("initial_upstream", initialUpstream)
            put("tls_mode", tlsMode)
            put("cloudflare_api_token", cloudflareApiToken.trim())
            put("acme_email", acmeEmail.trim())
            put("cert_file", certFile)
            put("key_file", keyFile)
            put("data_dir", dataDir)
        }.toString(2)
    }

    fun writeCoreConfigFile(context: Context): File {
        val dataDir = File(context.filesDir, "gateway_data").apply { mkdirs() }
        val certFile = File(context.filesDir, "manual_fullchain.pem").takeIf { tlsMode == "manual" }?.absolutePath ?: ""
        val keyFile = File(context.filesDir, "manual_privkey.pem").takeIf { tlsMode == "manual" }?.absolutePath ?: ""
        val configFile = File(dataDir, "config.json")
        configFile.writeText(toCoreJson(dataDir.absolutePath, certFile, keyFile))
        return configFile
    }

    companion object {
        private const val PREF_NAME = "carwithin_gateway_prefs"

        fun load(context: Context): GatewayConfig {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return GatewayConfig(
                domain = sp.getString("domain", "car.example.com") ?: "car.example.com",
                hijackIp = sp.getString("hijack_ip", "100.64.0.1") ?: "100.64.0.1",
                autoDdns = sp.getBoolean("auto_ddns", true),
                listenPort = sp.getInt("listen_port", 8443),
                controlPort = sp.getInt("control_port", 18443),
                tlsMode = sp.getString("tls_mode", "cloudflare") ?: "cloudflare",
                cloudflareApiToken = sp.getString("cf_token", "") ?: "",
                acmeEmail = sp.getString("acme_email", "") ?: "",
                targetPort = sp.getInt("target_port", 9888),
                targetMac = sp.getString("target_mac", "") ?: "",
                manualUpstreamIp = sp.getString("manual_upstream_ip", "") ?: "",
            )
        }

        fun save(context: Context, config: GatewayConfig) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                putString("domain", config.domain.trim())
                putString("hijack_ip", config.hijackIp.trim().ifEmpty { "100.64.0.1" })
                putBoolean("auto_ddns", config.autoDdns)
                putInt("listen_port", config.listenPort)
                putInt("control_port", config.controlPort)
                putString("tls_mode", config.tlsMode)
                putString("cf_token", config.cloudflareApiToken.trim())
                putString("acme_email", config.acmeEmail.trim())
                putInt("target_port", config.targetPort)
                putString("target_mac", config.targetMac.trim())
                putString("manual_upstream_ip", config.manualUpstreamIp.trim())
                apply()
            }
        }
    }
}
