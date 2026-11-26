package com.shzxm.carwithingateway

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class CoreActionResult(
    val exitCode: Int,
    val output: String,
)

object CoreRunner {
    suspend fun runAction(
        context: Context,
        config: GatewayConfig,
        action: String,
        streamToLog: Boolean = true,
    ): CoreActionResult = withContext(Dispatchers.IO) {
        val coreBin = File(context.applicationInfo.nativeLibraryDir, "libcarwithin_proxy.so")
        if (!coreBin.exists()) {
            val msg = "找不到内置代理核心: ${coreBin.absolutePath}"
            if (streamToLog) GatewayLog.append("[错误] $msg")
            return@withContext CoreActionResult(-1, msg)
        }

        val configFile = config.writeCoreConfigFile(context)
        try {
            val pb = ProcessBuilder(
                coreBin.absolutePath,
                "-config", configFile.absolutePath,
                "-action", action,
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line!!
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                    if (streamToLog) GatewayLog.append(text)
                }
            }
            val code = proc.waitFor()
            CoreActionResult(code, sb.toString())
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (streamToLog) GatewayLog.append("[错误] 执行 $action 失败: $msg")
            CoreActionResult(-1, msg)
        }
    }

    fun parseZoneNames(output: String): List<String> {
        val trimmed = output.trim()
        val startIdx = trimmed.indexOf('[')
        val endIdx = trimmed.lastIndexOf(']')
        if (startIdx < 0 || endIdx <= startIdx) return emptyList()
        return try {
            val arr = JSONArray(trimmed.substring(startIdx, endIdx + 1))
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name", "")
                if (name.isNotBlank()) list.add(name)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
