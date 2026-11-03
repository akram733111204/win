package com.rukn.expiry.network

import com.rukn.expiry.data.Product
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public catalogue: only a best-effort suggestion, never a verified expiry date. */
object ProductLookup {
    fun byBarcode(code: String): Product? {
        require(code.matches(Regex("[0-9]{6,18}"))) { "الباركود يجب أن يحتوي 6–18 رقمًا" }
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=code,product_name,product_name_ar,brands,categories,quantity,generic_name,ingredients_text")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RuknExpiry/2.0 (https://github.com/akram733111204/HYPER-b)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("خدمة المنتجات غير متاحة مؤقتًا: ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText().take(100_000) }
            val json = JSONObject(body)
            if (json.optInt("status", 0) != 1) return null
            val p = json.optJSONObject("product") ?: return null
            return Product(
                barcode = code,
                name = p.optString("product_name_ar").ifBlank { p.optString("product_name") },
                brand = p.optString("brands"),
                category = p.optString("categories"),
                notes = listOf(p.optString("generic_name"), p.optString("quantity"), p.optString("ingredients_text"))
                    .filter { it.isNotBlank() }.joinToString(" · ").take(500),
                source = "openfoodfacts"
            )
        } finally { conn.disconnect() }
    }
}
