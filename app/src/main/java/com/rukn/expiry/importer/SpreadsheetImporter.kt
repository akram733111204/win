package com.rukn.expiry.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import com.rukn.expiry.data.Product
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Data importer: deterministic column mapping, no speculative values or silent persistence. */
data class ImportPreview(
    val rows: List<Product>,
    val ignoredRows: Int,
    val detectedFields: Set<String>,
    val warnings: List<String>
)

object SpreadsheetImporter {
    const val LIMIT = 5000
    private const val MAX_BYTES = 20 * 1024 * 1024

    private val names = linkedMapOf(
        "name" to listOf("اسم المنتج", "الاسم", "اسم الصنف", "الصنف", "المنتج", "اسم المادة", "اسم البضاعة", "product", "product name", "item name", "name", "description name"),
        "barcode" to listOf("الباركود", "باركود", "الرمز الشريطي", "رقم الباركود", "الكود", "ean", "gtin", "upc", "barcode", "bar code", "sku"),
        "brand" to listOf("العلامة التجارية", "الشركة", "الماركة", "الصانع", "brand", "manufacturer"),
        "category" to listOf("التصنيف", "القسم", "الفئة", "المجموعة", "category", "group", "type"),
        "expiry" to listOf("تاريخ الانتهاء", "تاريخ انتهاء الصلاحية", "الصلاحية", "انتهاء", "تاريخ الانقضاء", "expiry", "expiration", "expiration date", "expiry date", "exp date", "best before"),
        "production" to listOf("تاريخ الإنتاج", "الانتاج", "تاريخ الصنع", "تاريخ التصنيع", "mfg", "manufacture date", "production date"),
        "quantity" to listOf("الكمية", "الرصيد", "عدد", "العدد", "مخزون", "quantity", "qty", "stock", "balance"),
        "unit" to listOf("الوحدة", "وحدة القياس", "الوحده", "unit", "uom"),
        "notes" to listOf("الوصف", "الملاحظات", "وصف", "التفاصيل", "description", "notes", "details"),
        "location" to listOf("الموقع", "المخزن", "مكان التخزين", "المستودع", "location", "warehouse", "shelf")
    )

    private fun key(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("[أإآ]"), "ا").replace('ة','ه').replace('ى','ي')
        .replace(Regex("[ًٌٍَُِّْـ]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    private fun field(raw: String): String? {
        val n = key(raw)
        if (n.isBlank()) return null
        return names.entries.firstOrNull { (_, synonyms) ->
            synonyms.any { alias -> key(alias) == n }
        }?.key
    }

    fun import(context: Context, uri: Uri, cloudHeaderMapper: ((List<String>) -> Map<String, Int>?)? = null): ImportPreview {
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().lowercase(Locale.ROOT)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (data.size() + n > MAX_BYTES) throw IllegalArgumentException("الملف أكبر من 20 ميغابايت")
                data.write(buf, 0, n)
            }
            data.toByteArray()
        } ?: throw IllegalArgumentException("تعذر فتح الملف")
        if (bytes.size >= 16 && String(bytes.copyOfRange(0, 15), Charsets.US_ASCII) == "SQLite format 3")
            return importSqlite(context, bytes, cloudHeaderMapper)
        if (bytes.take(2) == listOf(0x50.toByte(), 0x4b.toByte())) return fromSheets(readXlsx(bytes), cloudHeaderMapper)
        if (filename.endsWith(".xls")) throw IllegalArgumentException("ملفات XLS القديمة غير مدعومة في هذه النسخة؛ احفظها بصيغة XLSX أو CSV")
        return fromSheets(listOf(readCsv(bytes)), cloudHeaderMapper)
    }

    /** Keep header detection per sheet; duplicates are reviewed before save. */
    private fun fromSheets(sheets: List<List<List<String>>>, mapper: ((List<String>) -> Map<String, Int>?)? = null): ImportPreview {
        val products = mutableListOf<Product>()
        val warnings = mutableListOf<String>()
        val detected = mutableSetOf<String>()
        var ignored = 0
        for ((sheetIndex, rawRows) in sheets.withIndex()) {
            if (products.size >= LIMIT) break
            val rows = rawRows.take(LIMIT + 20)
            val best = rows.take(20).mapIndexed { i, cells ->
                val mapping = cells.mapIndexedNotNull { j, value -> field(value)?.let { it to j } }.toMap()
                Triple(i, mapping, mapping.size)
            }.maxByOrNull { it.third }
            val goodLocal = best != null && best.third > 0 && (best.second["name"] != null || best.second["barcode"] != null)
            val remote = if (!goodLocal && mapper != null && rows.isNotEmpty()) {
                runCatching { mapper(rows.first().take(40)) }.getOrNull()
            } else null
            val map = if (goodLocal) best!!.second else remote.orEmpty()
            val headerIndex = if (goodLocal) best!!.first else 0
            if (map.isEmpty() || (map["name"] == null && map["barcode"] == null)) {
                warnings += "الورقة ${sheetIndex + 1}: لم أتعرف على أعمدة الاسم أو الباركود؛ لم أخمّن محتوياتها."
                ignored += rows.size
                continue
            }
            if (!goodLocal) warnings += "الورقة ${sheetIndex + 1}: تم اقتراح تعيين الأعمدة عبر الذكاء الاصطناعي. راجع النتائج بعناية."
            detected.addAll(map.keys)
            val read = rows.drop(headerIndex + 1)
            for (cells in read) {
                if (products.size >= LIMIT) { warnings += "تم الاقتصار على أول $LIMIT صف"; break }
                fun get(name: String) = map[name]?.let { cells.getOrNull(it) }.orEmpty().trim()
                val product = Product(
                    name = get("name"), barcode = get("barcode").removeSuffix(".0"), brand = get("brand"),
                    category = get("category"), expiry = normalizeDate(get("expiry")),
                    production = normalizeDate(get("production")), quantity = get("quantity"),
                    unit = get("unit"), notes = get("notes"), location = get("location"), source = "import"
                )
                if (product.name.isBlank() && product.barcode.isBlank()) { ignored++; continue }
                if ((product.expiry.isNotBlank() && !isValidDate(product.expiry)) ||
                    (product.production.isNotBlank() && !isValidDate(product.production))) {
                    warnings += "صف ${headerIndex + 2}: تاريخ غير واضح في ${product.name.ifBlank { product.barcode }}؛ راجعه قبل الاستيراد."
                }
                products += product
            }
        }
        if (sheets.isEmpty()) warnings += "لم أجد جداول قابلة للقراءة"
        return ImportPreview(products, ignored, detected, warnings.take(40))
    }

    private fun isValidDate(value: String): Boolean = runCatching {
        if (value.length == 7) java.time.YearMonth.parse(value)
        else java.time.LocalDate.parse(value)
    }.isSuccess

    private fun normalizeDate(raw: String): String {
        val v = raw.trim().map { c ->
            when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }
        }.joinToString("")
        if (v.matches(Regex("\\d{4}/\\d{1,2}/\\d{1,2}"))) {
            val p = v.split('/'); return "%04d-%02d-%02d".format(Locale.ROOT, p[0].toInt(), p[1].toInt(), p[2].toInt())
        }
        if (v.matches(Regex("\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}"))) {
            val p = v.split(Regex("[/.-]"))
            return "%04d-%02d-%02d".format(Locale.ROOT, p[2].toInt(), p[1].toInt(), p[0].toInt())
        }
        if (v.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}"))) {
            val p = v.split('-'); return "%04d-%02d-%02d".format(Locale.ROOT, p[0].toInt(), p[1].toInt(), p[2].toInt())
        }
        if (v.matches(Regex("\\d{4}[-/]\\d{1,2}"))) {
            val p = v.split(Regex("[-/]")); return "%04d-%02d".format(Locale.ROOT, p[0].toInt(), p[1].toInt())
        }
        // Serial Excel dates require cell format context, so don't silently guess.
        return v
    }

    private fun readCsv(bytes: ByteArray): List<List<String>> {
        val sample = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val separator = listOf(',', ';', '\t', '|').maxByOrNull { ch -> sample.take(5000).count { it == ch } } ?: ','
        val result = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < sample.length && result.size <= LIMIT + 20) {
            val c = sample[i]
            when {
                c == '"' && quoted && i + 1 < sample.length && sample[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == separator -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) result.add(row.toList())
                    row.clear()
                    if (c == '\r' && i + 1 < sample.length && sample[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) result.add(row)
        return result
    }

    private fun readXlsx(bytes: ByteArray): List<List<List<String>>> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalArgumentException("ملف XLSX كبير جدًا بعد فك الضغط")
                        out.write(chunk, 0, n)
                    }
                    entries[name] = out.toByteArray()
                }
            }
        }
        val strings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return entries.filterKeys { it.startsWith("xl/worksheets/") }.toSortedMap(
            compareBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        ).values.map { parseSheet(it, strings) }
    }

    private fun sax(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val values = mutableListOf<String>()
        sax(bytes, object : DefaultHandler() {
            private var inside = false
            private var text = false
            private val item = StringBuilder()
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName.substringAfter(':')) {
                    "si" -> { inside = true; item.setLength(0) }
                    "t" -> if (inside) text = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (text) item.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "t" -> text = false
                    "si" -> { values += item.toString(); inside = false }
                }
            }
        })
        return values
    }

    private fun columnIndex(address: String): Int {
        var n = 0
        for (char in address.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)) n = n * 26 + (char - 'A' + 1)
        return n - 1
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        sax(bytes, object : DefaultHandler() {
            private val row = linkedMapOf<Int, String>()
            private var cellIndex = -1
            private var cellType = ""
            private var collecting = false
            private val value = StringBuilder()
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName.substringAfter(':')) {
                    "row" -> row.clear()
                    "c" -> { cellIndex = columnIndex(attrs?.getValue("r").orEmpty()); cellType = attrs?.getValue("t").orEmpty(); value.setLength(0) }
                    "v", "t" -> if (cellIndex in 0..200) { collecting = true }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collecting) value.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "v", "t" -> collecting = false
                    "c" -> if (cellIndex in 0..200) {
                        val text = if (cellType == "s") shared.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty() else value.toString()
                        row[cellIndex] = text
                    }
                    "row" -> if (rows.size < LIMIT + 20 && row.isNotEmpty()) {
                        val max = (row.keys.maxOrNull() ?: 0).coerceAtMost(200)
                        rows += (0..max).map { row[it].orEmpty() }
                    }
                }
            }
        })
        return rows
    }

    /** Local SQLite file: read-only, table schema inspection, never execute imported SQL. */
    private fun importSqlite(context: Context, bytes: ByteArray, mapper: ((List<String>) -> Map<String, Int>?)?): ImportPreview {
        val temp = File.createTempFile("rukn_import_", ".db", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val sheets = mutableListOf<List<List<String>>>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { tables ->
                    while (tables.moveToNext() && sheets.size < 20) {
                        val table = tables.getString(0)
                        val safe = "\"${table.replace("\"", "\"\"")}\""
                        db.rawQuery("SELECT * FROM $safe LIMIT ${LIMIT + 20}", null).use { cursor ->
                            val rows = mutableListOf<List<String>>()
                            rows += cursor.columnNames.toList()
                            while (cursor.moveToNext()) {
                                rows += (0 until cursor.columnCount).map { i ->
                                    if (cursor.isNull(i)) "" else if (cursor.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "" else cursor.getString(i).orEmpty()
                                }
                            }
                            sheets += rows
                        }
                    }
                }
                return fromSheets(sheets, mapper)
            } finally { db.close() }
        } finally { temp.delete() }
    }
}
