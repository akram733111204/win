package com.rukn.expiry.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rukn.core.VoiceField
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Persistent, local-only inventory; no account and no server required. */
data class Product(
    val id: Long = 0,
    val name: String = "",
    val barcode: String = "",
    val brand: String = "",
    val category: String = "",
    val expiry: String = "",
    val production: String = "",
    val quantity: String = "",
    val unit: String = "",
    val notes: String = "",
    val location: String = "",
    val source: String = "manual"
) {
    fun toVoiceFields(): Map<VoiceField, String> = mapOf(
        VoiceField.PRODUCT_NAME_AR to name,
        VoiceField.BARCODE to barcode,
        VoiceField.BRAND to brand,
        VoiceField.CATEGORY to category,
        VoiceField.EXPIRY_DATE to expiry,
        VoiceField.PRODUCTION_DATE to production,
        VoiceField.QUANTITY to quantity,
        VoiceField.UNIT to unit,
        VoiceField.PRODUCT_NOTES to notes,
        VoiceField.LOCATION to location
    )

    fun mergeVoiceFields(fields: Map<VoiceField, String>): Product = copy(
        name = fields[VoiceField.PRODUCT_NAME_AR] ?: name,
        barcode = fields[VoiceField.BARCODE] ?: barcode,
        brand = fields[VoiceField.BRAND] ?: brand,
        category = fields[VoiceField.CATEGORY] ?: category,
        expiry = fields[VoiceField.EXPIRY_DATE] ?: expiry,
        production = fields[VoiceField.PRODUCTION_DATE] ?: production,
        quantity = fields[VoiceField.QUANTITY] ?: quantity,
        unit = fields[VoiceField.UNIT] ?: unit,
        notes = fields[VoiceField.PRODUCT_NOTES] ?: notes,
        location = fields[VoiceField.LOCATION] ?: location
    )
}

fun expiryDays(date: String): Long? = runCatching {
    val parsed = if (date.length == 7) YearMonth.parse(date).atEndOfMonth() else LocalDate.parse(date)
    ChronoUnit.DAYS.between(LocalDate.now(), parsed)
}.getOrNull()

class ProductStore(context: Context) : SQLiteOpenHelper(context, "rukn_inventory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE products (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL, barcode TEXT NOT NULL DEFAULT '', brand TEXT NOT NULL DEFAULT '',
          category TEXT NOT NULL DEFAULT '', expiry TEXT NOT NULL DEFAULT '',
          production TEXT NOT NULL DEFAULT '', quantity TEXT NOT NULL DEFAULT '',
          unit TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
          location TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'manual',
          modified INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE UNIQUE INDEX products_barcode_unique ON products(barcode) WHERE barcode != ''")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add future migrations here. Never DROP a user's inventory.
    }

    fun all(): List<Product> {
        val results = mutableListOf<Product>()
        readableDatabase.rawQuery("SELECT id,name,barcode,brand,category,expiry,production,quantity,unit,notes,location,source FROM products ORDER BY modified DESC,id DESC", null).use { c ->
            while (c.moveToNext()) results += Product(
                id = c.getLong(0), name = c.getString(1), barcode = c.getString(2),
                brand = c.getString(3), category = c.getString(4), expiry = c.getString(5),
                production = c.getString(6), quantity = c.getString(7), unit = c.getString(8),
                notes = c.getString(9), location = c.getString(10), source = c.getString(11)
            )
        }
        return results
    }

    fun findByBarcode(code: String): Product? {
        if (code.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT id,name,barcode,brand,category,expiry,production,quantity,unit,notes,location,source FROM products WHERE barcode=? LIMIT 1",
            arrayOf(code)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Product(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),
                c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9),c.getString(10),c.getString(11))
        }
    }

    /** Upsert on barcode only. Never overwrite an existing product by name guesses. */
    @Synchronized fun save(product: Product): Long {
        require(product.name.isNotBlank() || product.barcode.isNotBlank()) { "اسم المنتج أو باركوده مطلوب" }
        for (date in listOf(product.expiry, product.production)) {
            if (date.isNotBlank()) require(expiryDays(date) != null) { "تاريخ غير صحيح: $date (استخدم YYYY-MM-DD أو YYYY-MM)" }
        }
        val db = writableDatabase
        val existing = if (product.id > 0L) product.id else {
            product.barcode.takeIf { it.isNotBlank() }?.let { findByBarcode(it)?.id } ?: 0L
        }
        val values = ContentValues().apply {
            put("name", product.name.trim()); put("barcode", product.barcode.trim())
            put("brand", product.brand.trim()); put("category", product.category.trim())
            put("expiry", product.expiry.trim()); put("production", product.production.trim())
            put("quantity", product.quantity.trim()); put("unit", product.unit.trim())
            put("notes", product.notes.trim()); put("location", product.location.trim())
            put("source", product.source); put("modified", System.currentTimeMillis())
        }
        if (existing > 0L) {
            require(db.update("products", values, "id=?", arrayOf(existing.toString())) > 0) { "المنتج غير موجود" }
            return existing
        }
        return db.insertOrThrow("products", null, values)
    }

    fun remove(id: Long) { writableDatabase.delete("products", "id=?", arrayOf(id.toString())) }
}
