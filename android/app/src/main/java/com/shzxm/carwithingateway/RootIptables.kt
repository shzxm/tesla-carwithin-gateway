package com.shzxm.carwithingateway

import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object RootIptables {
    private val ipRegex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun runRoot(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "su execution failed")
        }
    }

    fun hasRoot(): Boolean {
        val res = runRoot("id")
        return res.exitCode == 0 && res.stdout.contains("uid=0")
    }

    fun applyRules(hijackIp: String, port: Int): Result<Unit> {
        val ip = hijackIp.trim()
        if (!ipRegex.matches(ip) || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("无效的劫持 IP 或端口: $ip:$port"))
        }
        val natCheck = "iptables -t nat -C PREROUTING -d $ip -p tcp --dport $port -j REDIRECT --to-ports $port"
        val natAdd = "iptables -t nat -A PREROUTING -d $ip -p tcp --dport $port -j REDIRECT --to-ports $port"
        val inCheck = "iptables -C INPUT -p tcp --dport $port -j ACCEPT"
        val inAdd = "iptables -I INPUT -p tcp --dport $port -j ACCEPT"

        val script = """
            ($natCheck 2>/dev/null || $natAdd) && ($inCheck 2>/dev/null || $inAdd)
        """.trimIndent()

        val res = runRoot(script)
        return if (res.exitCode == 0) {
            GatewayLog.append("[iptables] 已启用劫持规则: $ip:$port -> 本机 :$port")
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("iptables 执行失败 (code=${res.exitCode}): ${res.stderr}"))
        }
    }

    fun removeRules(hijackIp: String, port: Int) {
        val ip = hijackIp.trim()
        if (!ipRegex.matches(ip) || port !in 1..65535) return
        val script = """
            while iptables -t nat -D PREROUTING -d $ip -p tcp --dport $port -j REDIRECT --to-ports $port 2>/dev/null; do :; done
            while iptables -D INPUT -p tcp --dport $port -j ACCEPT 2>/dev/null; do :; done
        """.trimIndent()
        runRoot(script)
        GatewayLog.append("[iptables] 已清理劫持规则: $ip:$port")
    }
}
