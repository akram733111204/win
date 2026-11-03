package com.rukn.expiry.network

import com.rukn.core.VoiceAction
import com.rukn.core.VoiceCommand
import com.rukn.core.VoiceCommandEngine
import com.rukn.core.VoiceField
import com.rukn.core.VoiceResult
import com.rukn.expiry.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional backend. URL comes from build configuration; no end-user server setup. */
object CloudInterpreter {
    val configured: Boolean get() = BuildConfig.AI_BASE_URL.startsWith("https://")

    /** Only column headers are sent, never product rows. Requires explicit in-app opt-in. */
    fun mapHeaders(headers: List<String>): Map<String, Int>? {
        if (!configured) return null
        val payload = JSONObject().put("headers", org.json.JSONArray(headers.take(40)))
        val response = request("/v1/map-columns", payload) ?: return null
        val map = response.optJSONObject("mapping") ?: return null
        val keys = setOf("name", "barcode", "brand", "category", "expiry", "production", "quantity", "unit", "notes", "location")
        val result = mutableMapOf<String, Int>()
        for (key in keys) {
            val index = map.optInt(key, -1)
            if (index in headers.indices) result[key] = index
        }
        return result.takeIf { it.containsKey("name") || it.containsKey("barcode") }
    }

    private fun request(path: String, payload: JSONObject): JSONObject? {
        if (!configured) return null
        val conn = (URL("${BuildConfig.AI_BASE_URL}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText().take(40_000) }
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    fun interpret(text: String): VoiceResult? {
        if (!configured) return null
        val json = request("/v1/interpret", JSONObject().put("transcript", text.take(3000))) ?: return null
            val list = mutableListOf<VoiceCommand>()
            val commands = json.optJSONArray("commands") ?: return null
            for (i in 0 until minOf(commands.length(), 32)) {
                val item = commands.optJSONObject(i) ?: continue
                val field = runCatching { VoiceField.valueOf(item.optString("field")) }.getOrNull() ?: continue
                val action = runCatching { VoiceAction.valueOf(item.optString("action", "SET")) }.getOrNull() ?: continue
                list += VoiceCommand(field, action, item.optString("value").take(400))
            }
            val warnings = mutableListOf<String>()
            json.optJSONArray("warnings")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 10)) warnings += arr.optString(i).take(200)
            }
            return VoiceCommandEngine.validate(VoiceResult(list, warnings, "cloud"))
    }
}
