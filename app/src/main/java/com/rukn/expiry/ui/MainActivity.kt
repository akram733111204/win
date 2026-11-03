package com.rukn.expiry.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.rukn.core.VoiceCommandEngine
import com.rukn.core.VoiceField
import com.rukn.core.VoiceResult
import com.rukn.expiry.data.Product
import com.rukn.expiry.data.ProductStore
import com.rukn.expiry.data.expiryDays
import com.rukn.expiry.importer.ImportPreview
import com.rukn.expiry.importer.SpreadsheetImporter
import com.rukn.expiry.network.CloudInterpreter
import com.rukn.expiry.network.ProductLookup
import com.rukn.expiry.voice.ArabicSpeech
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Arabic RTL interface with confirmation-based edits and no required cloud service. */
class MainActivity : Activity() {
    private lateinit var store: ProductStore
    private lateinit var speech: ArabicSpeech
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var screen: LinearLayout
    private lateinit var body: LinearLayout
    private var tab = "home"
    private var voiceBase: Product? = null
    private var status: TextView? = null

    private val importRequest = 1001
    private val exportRequest = 1003
    private val microphoneRequest = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.navy
        window.navigationBarColor = Palette.white
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        store = ProductStore(this)
        speech = ArabicSpeech(this)
        show("home")
    }

    private fun show(target: String) {
        tab = target
        screen = vertical().apply {
            setBackgroundColor(Palette.canvas)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(screen)
        if (Build.VERSION.SDK_INT >= 35) {
            screen.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }

        val banner = vertical().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Palette.navy, Palette.navy2)
            )
            setPadding(dp(22), dp(17), dp(22), dp(19))
        }
        val head = horizontal()
        head.addView(label("◈  رُكـن", 25f, Palette.white, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label("إدارة المخزون والصلاحية", 12f, 0xFFC2E3D9.toInt()))
        banner.addView(head)
        banner.addView(columnSpace(6))
        banner.addView(label("مساعدك الذكي للمخزون، من أول منتج إلى آخر تاريخ صلاحية.", 12f, 0xFFD5E8E6.toInt()))
        screen.addView(banner, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
        }
        body = vertical().apply { setPadding(dp(17), dp(18), dp(17), dp(22)) }
        scroll.addView(body)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(navigation(), LinearLayout.LayoutParams(-1, -2))

        when (target) {
            "products" -> productsPage()
            "voice" -> voicePage()
            "settings" -> settingsPage()
            else -> homePage()
        }
    }

    private fun navigation(): LinearLayout {
        val nav = horizontal().apply {
            setPadding(dp(5), dp(8), dp(5), dp(12))
            background = rounded(Palette.white, dp(0).toFloat())
        }
        listOf("home" to "⌂  الرئيسية", "products" to "▤  المنتجات", "voice" to "●  المساعد", "settings" to "⚙  الإعدادات").forEach { (key, value) ->
            nav.addView(label(value, 12f, if (tab == key) Palette.green else Palette.grey, tab == key).apply {
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(12), dp(5), dp(12))
                setOnClickListener { show(key) }
                if (tab == key) background = rounded(Palette.greenLight, dp(12).toFloat())
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun sectionTitle(title: String, subtitle: String? = null) {
        body.addView(label(title, 19f, Palette.navy, true))
        subtitle?.let { body.addView(label(it, 12f, Palette.grey)) }
        body.addView(columnSpace(13))
    }

    private fun homePage() {
        val all = store.all()
        val past = all.count { expiryDays(it.expiry)?.let { d -> d < 0 } == true }
        val near = all.count { expiryDays(it.expiry)?.let { d -> d in 0..30 } == true }
        val hero = vertical().apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF128C72.toInt(), 0xFF19B890.toInt())).apply { cornerRadius = dp(23).toFloat() }
            setPadding(dp(21), dp(21), dp(21), dp(21))
        }
        hero.addView(label("نظرة عامة على مخزونك", 20f, Palette.white, true))
        hero.addView(label("كل ما تحتاجه في مكان واحد", 12f, 0xFFDDFCF2.toInt()))
        hero.addView(columnSpace(17))
        val stats = horizontal()
        stats.addView(stat("${all.size}", "المنتجات"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(rowSpace(7))
        stats.addView(stat("$near", "قريب الانتهاء"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(rowSpace(7))
        stats.addView(stat("$past", "منتهي"), LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(stats)
        body.addView(hero)
        body.addView(columnSpace(21))

        sectionTitle("ابدأ بسرعة", "طرق سهلة وآمنة لإدخال بيانات المنتجات")
        val actions = card()
        actions.addView(actionRow("🎙", "تحدث بالعربية", "أضف منتجًا أو عدّل صلاحيته بصوتك") { startVoice(null) })
        actions.addView(columnSpace(14))
        actions.addView(actionRow("▦", "امسح الباركود", "اعرض بيانات المنتج من قاعدة البيانات العامة") { scanBarcode() })
        actions.addView(columnSpace(14))
        actions.addView(actionRow("⇧", "استيراد Excel أو قاعدة بيانات", "CSV / XLSX / SQLite مع معاينة أولًا") { pickImport() })
        actions.addView(columnSpace(14))
        actions.addView(actionRow("＋", "إضافة منتج يدويًا", "أنشئ سجلًا جديدًا في ثوانٍ") { editProduct(Product()) })
        body.addView(actions)
        body.addView(columnSpace(22))

        sectionTitle("تحتاج إلى انتباهك", "التواريخ القريبة والمنتهية")
        val urgent = all.filter { expiryDays(it.expiry)?.let { d -> d <= 30 } == true }
            .sortedBy { expiryDays(it.expiry) }.take(6)
        if (urgent.isEmpty()) {
            val empty = card()
            empty.addView(label("✓  لا توجد تنبيهات صلاحية حاليًا", 14f, Palette.green, true))
            empty.addView(label("أضف منتجاتك مع تواريخ الصلاحية لتظهر هنا.", 12f, Palette.grey))
            body.addView(empty)
        } else urgent.forEach { productRow(it) }
    }

    private fun stat(number: String, title: String): LinearLayout = vertical().apply {
        setPadding(dp(9), dp(11), dp(9), dp(11))
        background = rounded(0x33FFFFFF, dp(15).toFloat())
        addView(label(number, 22f, Palette.white, true))
        addView(label(title, 11f, Palette.white))
    }

    private fun actionRow(icon: String, title: String, description: String, click: () -> Unit): LinearLayout = horizontal().apply {
        addView(label(icon, 28f, Palette.green).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.greenLight, dp(15).toFloat())
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(rowSpace(12))
        val txt = vertical()
        txt.addView(label(title, 15f, Palette.navy, true))
        txt.addView(label(description, 12f, Palette.grey))
        addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("‹", 26f, Palette.green))
        setOnClickListener { click() }
    }

    private fun productsPage() {
        sectionTitle("سجل المنتجات", "بياناتك محفوظة محليًا على هاتفك")
        val row = horizontal()
        row.addView(action("＋ إضافة", true) { editProduct(Product()) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rowSpace(8))
        row.addView(action("▦ الباركود") { scanBarcode() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        body.addView(columnSpace(17))
        val all = store.all()
        if (all.isEmpty()) {
            val empty = card()
            empty.addView(label("لا توجد منتجات بعد", 18f, Palette.navy, true))
            empty.addView(label("ابدأ بإضافة منتج، أو استيراد قائمة من Excel.", 13f, Palette.grey))
            empty.addView(columnSpace(16))
            empty.addView(action("استيراد ملف") { pickImport() })
            body.addView(empty)
        } else all.forEach { productRow(it) }
    }

    private fun productRow(product: Product) {
        val tile = card().apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        val row = horizontal()
        val detail = vertical()
        detail.addView(label(product.name.ifBlank { "منتج دون اسم" }, 15f, Palette.navy, true))
        val sub = listOf(product.brand, product.barcode).filter { it.isNotBlank() }.joinToString("  •  ")
        if (sub.isNotBlank()) detail.addView(label(sub, 11f, Palette.grey))
        row.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
        val days = expiryDays(product.expiry)
        val badgeText = when {
            days == null -> "دون تاريخ"
            days < 0 -> "منتهي"
            days <= 30 -> "تبقى $days يوم"
            else -> "سليم"
        }
        row.addView(label(badgeText, 11f, if (days != null && days < 0) Palette.red else if (days != null && days <= 30) Palette.orange else Palette.green, true).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(if (days != null && days < 0) 0xFFFCE8E8.toInt() else if (days != null && days <= 30) 0xFFFFF4DE.toInt() else Palette.greenLight, dp(10).toFloat())
        })
        tile.addView(row)
        if (product.expiry.isNotBlank() || product.quantity.isNotBlank()) {
            tile.addView(columnSpace(8))
            tile.addView(label("الصلاحية: ${product.expiry.ifBlank { "—" }}      الكمية: ${product.quantity.ifBlank { "—" }} ${product.unit}", 12f, Palette.grey))
        }
        tile.setOnClickListener { editProduct(product) }
        body.addView(tile)
        body.addView(columnSpace(9))
    }

    private fun voicePage() {
        sectionTitle("المساعد الصوتي العربي", "قل اسم الحقل والقيمة بصورة طبيعية")
        val card = card()
        card.addView(label("🎙", 40f, Palette.green).apply { gravity = Gravity.CENTER })
        card.addView(label("دون إدخال عنوان خادم", 19f, Palette.navy, true).apply { gravity = Gravity.CENTER })
        card.addView(label("يستخدم خدمة التعرف على الصوت المتوفرة على هاتفك. يمكنك إضافة ذكاء سحابي اختياري عبر إعداد المشروع.", 13f, Palette.grey).apply { gravity = Gravity.CENTER })
        card.addView(columnSpace(17))
        card.addView(action("ابدأ التحدث الآن") { startVoice(null) })
        card.addView(columnSpace(14))
        card.addView(label("مثال: تاريخ الانتهاء 2027-12-30 والكمية 15 حبة", 12f, Palette.grey))
        card.addView(columnSpace(6))
        card.addView(label("مثال: غير سنة الانتهاء إلى 2027 (بعد فتح المنتج)", 12f, Palette.grey))
        body.addView(card)
        body.addView(columnSpace(17))
        sectionTitle("جرّب نصًا مكتوبًا", "اختبر فهم الأوامر بدون ميكروفون")
        val textBox = card()
        val input = editorInput("اكتب أمرًا بالعربية", "تاريخ الانتهاء 2027-12-30")
        textBox.addView(input)
        textBox.addView(columnSpace(10))
        textBox.addView(action("تحليل الأمر ومراجعته", true) { parseVoice(input.text.toString(), null) })
        body.addView(textBox)
    }

    private fun settingsPage() {
        sectionTitle("الخصوصية والخدمات", "تحكم واضح دون إعدادات معقدة")
        val info = card()
        info.addView(label("✓  تعرف على الصوت من أندرويد", 16f, Palette.navy, true))
        info.addView(label("لا يحتاج إلى مفتاح Gemini أو رابط خادم. وضع العمل دون إنترنت يعتمد على توفر حزمة العربية على جهازك.", 13f, Palette.grey))
        info.addView(columnSpace(17))
        info.addView(label(if (CloudInterpreter.configured) "✓  خدمة الذكاء السحابي مفعّلة في هذا البناء" else "•  الذكاء السحابي الإضافي غير مهيأ", 15f, if (CloudInterpreter.configured) Palette.green else Palette.grey, true))
        info.addView(label("يعمل الإدخال الصوتي المحلي والباركود والاستيراد دون إعداد سحابي. لتحسين فهم العبارات غير المدعومة يمكن لمسؤول التطبيق ربط الخادم عند البناء، دون كشف مفاتيح الخدمات للمستخدم.", 12f, Palette.grey))
        info.addView(columnSpace(17))
        info.addView(label("بياناتك تحت سيطرتك", 16f, Palette.navy, true))
        info.addView(label("المخزون محفوظ محليًا. لا تُرسل الأوامر إلى خدمة سحابية إلا عند تهيئتها. البحث بالباركود يرسل الرقم إلى Open Food Facts فقط عند طلبك.", 12f, Palette.grey))
        body.addView(info)
        body.addView(columnSpace(18))
        val prefs = getSharedPreferences("rukn_settings", MODE_PRIVATE)
        val aiMapping = prefs.getBoolean("cloud_headers", false)
        val toggleLabel = if (aiMapping) "✓ تحليل أعمدة الملفات بالذكاء السحابي: مفعّل" else "تحليل أعمدة الملفات بالذكاء السحابي: معطّل"
        body.addView(action(toggleLabel, true) {
            if (!CloudInterpreter.configured) {
                notice("لا يوجد خادم ذكاء سحابي مهيأ في هذه النسخة؛ الاستيراد المحلي ما زال يعمل.")
            } else {
                prefs.edit().putBoolean("cloud_headers", !aiMapping).apply()
                show("settings")
            }
        })
        body.addView(columnSpace(7))
        body.addView(label("عند التفعيل تُرسل أسماء الأعمدة فقط إلى مزود الذكاء الاصطناعي إن تعذر التعرف عليها محليًا، دون إرسال صفوف المنتجات.", 12f, Palette.grey))
        body.addView(columnSpace(18))
        body.addView(action("استيراد بيانات من ملف") { pickImport() })
        body.addView(columnSpace(10))
        body.addView(action("تصدير نسخة احتياطية CSV", true) { exportCsv() })
    }

    private fun editorInput(hint: String, initial: String): EditText = EditText(this).apply {
        setSingleLine(true)
        setText(initial)
        this.hint = hint
        textSize = 15f
        setTextColor(Palette.navy)
        setHintTextColor(Palette.grey)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(Palette.canvas, dp(12).toFloat(), Palette.border)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun editProduct(product: Product) {
        val wrapper = ScrollView(this)
        val form = vertical().apply { setPadding(dp(19), dp(12), dp(19), dp(14)) }
        wrapper.addView(form)
        val keys = listOf(
            Triple("اسم المنتج", product.name, "name"),
            Triple("الباركود", product.barcode, "barcode"),
            Triple("العلامة التجارية", product.brand, "brand"),
            Triple("الفئة", product.category, "category"),
            Triple("تاريخ الانتهاء YYYY-MM-DD", product.expiry, "expiry"),
            Triple("تاريخ الإنتاج YYYY-MM-DD", product.production, "production"),
            Triple("الكمية", product.quantity, "quantity"),
            Triple("الوحدة (حبة / كرتون)", product.unit, "unit"),
            Triple("مكان التخزين", product.location, "location"),
            Triple("الوصف والملاحظات", product.notes, "notes")
        )
        val edits = linkedMapOf<String, EditText>()
        keys.forEach { (title, start, key) ->
            form.addView(label(title, 12f, Palette.grey, true))
            form.addView(columnSpace(5))
            edits[key] = editorInput(title, start).also { form.addView(it) }
            form.addView(columnSpace(12))
        }
        var editingDialog: AlertDialog? = null
        val buttons = horizontal()
        buttons.addView(action("🎙 تعديل صوتي", true) {
            val current = productFromInputs(product, edits)
            editingDialog?.dismiss()
            startVoice(current)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(rowSpace(8))
        buttons.addView(action("▦ بحث باركود") {
            lookupBarcode(edits["barcode"]?.text.toString()) { found ->
                editingDialog?.dismiss()
                editProduct(found.copy(
                    id = product.id,
                    expiry = product.expiry,
                    production = product.production,
                    quantity = product.quantity,
                    unit = product.unit,
                    location = product.location
                ))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        form.addView(buttons)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (product.id > 0) "تعديل المنتج" else "إضافة منتج جديد")
            .setView(wrapper)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ المنتج", null)
            .create()
        editingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val result = productFromInputs(product, edits)
                    store.save(result)
                    speech.speak("تم حفظ المنتج")
                    dialog.dismiss()
                    show("products")
                } catch (e: Exception) { notice(e.localizedMessage ?: "خطأ في البيانات") }
            }
        }
        dialog.show()
        // setOnShowListener installed before show, ensures failed validation doesn't close dialog.
    }

    private fun productFromInputs(template: Product, edits: Map<String, EditText>): Product {
        fun get(key: String) = edits[key]?.text?.toString()?.trim().orEmpty()
        return template.copy(
            name = get("name"), barcode = get("barcode"), brand = get("brand"),
            category = get("category"), expiry = get("expiry"), production = get("production"),
            quantity = get("quantity"), unit = get("unit"),
            location = get("location"), notes = get("notes")
        )
    }

    private fun startVoice(base: Product?) {
        voiceBase = base
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), microphoneRequest)
            return
        }
        status = label("أجهز الميكروفون…", 16f, Palette.navy)
        val dlg = AlertDialog.Builder(this)
            .setTitle("المساعد الصوتي العربي")
            .setView(vertical().apply {
                setPadding(dp(20), dp(18), dp(20), dp(18))
                status?.let { addView(it) }
            })
            .setNegativeButton("إيقاف") { _, _ -> speech.stop() }
            .create()
        dlg.setOnCancelListener { speech.stop() }
        dlg.show()
        speech.start(
            onText = { result -> dlg.dismiss(); parseVoice(result, base) },
            onError = { reason -> dlg.dismiss(); notice(reason) },
            onProgress = { message -> status?.text = message }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == microphoneRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice(voiceBase)
            else notice("لا يمكن تشغيل التسجيل دون السماح باستخدام الميكروفون")
        }
    }

    private fun parseVoice(transcript: String, base: Product?) {
        if (transcript.isBlank()) { notice("اكتب أو انطق كلامًا أولًا"); return }
        val local = VoiceCommandEngine.interpret(transcript)
        if (!local.isEmpty || !CloudInterpreter.configured) {
            previewCommand(transcript, base, local)
        } else {
            notice("أبحث عن تفسير إضافي للعبارة…")
            worker.execute {
                val cloud = runCatching { CloudInterpreter.interpret(transcript) }.getOrNull()
                runOnUiThread { previewCommand(transcript, base, cloud?.takeUnless { it.isEmpty } ?: local) }
            }
        }
    }

    private fun previewCommand(transcript: String, base: Product?, parsed: VoiceResult) {
        val lines = parsed.commands.joinToString("\n") { "• ${fieldArabic(it.field)}  —  ${it.action.name}: ${it.value}" }
        val warnings = parsed.warnings.joinToString("\n") { "⚠ $it" }
        if (parsed.commands.isEmpty()) {
            AlertDialog.Builder(this).setTitle("لم أتعرف على الحقول")
                .setMessage("النص المسموع:\n$transcript\n\n$warnings\n\nجرّب: تاريخ الانتهاء 2027-12-30، الكمية 12 حبة")
                .setPositiveButton("حسنًا", null).show()
            return
        }
        val target = base ?: Product()
        val application = VoiceCommandEngine.apply(target.toVoiceFields(), parsed)
        val proposed = target.mergeVoiceFields(application.values)
        val finalWarnings = (parsed.warnings + application.warnings).distinct()
        val appliedLines = application.changed.joinToString("\n") { key ->
            "• ${fieldArabic(key)}: ${application.values[key].orEmpty()}"
        }.ifBlank { "لم يتم تطبيق أي تغيير؛ راجع التحذيرات قبل المتابعة." }
        AlertDialog.Builder(this)
            .setTitle("مراجعة الأمر قبل الحفظ")
            .setMessage("سمعت: $transcript\n\nما فهمته:\n$lines\n\nالتغييرات الصالحة فقط:\n$appliedLines\n\n${finalWarnings.joinToString("\n") { "⚠ $it" }}\n\nلن يُحفظ أي شيء تلقائيًا.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("مراجعة بيانات المنتج") { _, _ -> editProduct(proposed) }
            .show()
    }

    private fun fieldArabic(field: VoiceField): String = when (field) {
        VoiceField.EXPIRY_DATE -> "تاريخ الانتهاء"
        VoiceField.PRODUCTION_DATE -> "تاريخ الإنتاج"
        VoiceField.QUANTITY -> "الكمية"
        VoiceField.PRODUCT_NAME_AR -> "اسم المنتج"
        VoiceField.BARCODE -> "الباركود"
        VoiceField.UNIT -> "الوحدة"
        VoiceField.BRAND -> "العلامة التجارية"
        else -> field.name
    }

    private fun scanBarcode() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue.orEmpty()
                if (code.isBlank()) notice("لم يتم التقاط باركود واضح")
                else lookupBarcode(code) { product -> editProduct(product) }
            }
            .addOnFailureListener { e ->
                val input = editorInput("أدخل الباركود يدويًا", "")
                AlertDialog.Builder(this).setTitle("الماسح غير متاح")
                    .setMessage("قد يحتاج الماسح إلى خدمات Google Play أو تنزيل وحدة المسح. يمكنك الإدخال يدويًا.")
                    .setView(input)
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("بحث") { _, _ -> lookupBarcode(input.text.toString()) { p -> editProduct(p) } }
                    .show()
            }
    }

    private fun lookupBarcode(raw: String, callback: (Product) -> Unit) {
        val code = raw.trim()
        if (!code.matches(Regex("[0-9]{6,18}"))) { notice("أدخل باركود رقميًا من 6 إلى 18 رقمًا"); return }
        store.findByBarcode(code)?.let { local ->
            AlertDialog.Builder(this).setTitle("منتج محفوظ على جهازك")
                .setMessage("${local.name}\nالباركود: $code")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فتح المنتج") { _, _ -> callback(local) }.show()
            return
        }
        notice("أبحث عن المنتج بالباركود…")
        worker.execute {
            val result = runCatching { ProductLookup.byBarcode(code) }
            runOnUiThread {
                if (result.isFailure) {
                    notice("تعذر جلب بيانات المنتج: ${result.exceptionOrNull()?.localizedMessage}")
                    callback(Product(barcode = code))
                } else {
                    val product = result.getOrNull()
                    if (product == null) {
                        notice("لم أجد هذا الباركود في قاعدة البيانات العامة؛ يمكنك إضافة بياناته يدويًا")
                        callback(Product(barcode = code))
                    } else callback(product)
                }
            }
        }
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, importRequest)
    }

    @Deprecated("Use ActivityResult API in a future upgrade")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == exportRequest) {
            writeExport(uri)
            return
        }
        if (requestCode != importRequest) return
        notice("أحلل الأعمدة والصفوف…")
        worker.execute {
            val shareHeaders = getSharedPreferences("rukn_settings", MODE_PRIVATE).getBoolean("cloud_headers", false)
            val loaded = runCatching {
                SpreadsheetImporter.import(this, uri, if (shareHeaders && CloudInterpreter.configured) CloudInterpreter::mapHeaders else null)
            }
            runOnUiThread {
                loaded.onSuccess { showImportPreview(it) }
                    .onFailure { notice("تعذر استيراد الملف: ${it.localizedMessage}") }
            }
        }
    }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "rukn-products-backup.csv")
        }
        startActivityForResult(intent, exportRequest)
    }

    private fun writeExport(uri: android.net.Uri) {
        worker.execute {
            val result = runCatching {
                val products = store.all()
                val headers = listOf("اسم المنتج", "الباركود", "العلامة التجارية", "التصنيف", "تاريخ الانتهاء", "تاريخ الإنتاج", "الكمية", "الوحدة", "الوصف", "الموقع")
                fun escaped(value: String): String {
                    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + value else value
                    return "\"" + safe.replace("\"", "\"\"") + "\""
                }
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { out ->
                    out.write("\uFEFF")
                    out.write(headers.joinToString(",", transform = ::escaped)); out.newLine()
                    products.forEach { p ->
                        out.write(listOf(p.name,p.barcode,p.brand,p.category,p.expiry,p.production,p.quantity,p.unit,p.notes,p.location)
                            .joinToString(",", transform = ::escaped))
                        out.newLine()
                    }
                } ?: error("تعذر الكتابة على الملف المحدد")
                products.size
            }
            runOnUiThread {
                result.onSuccess { notice("تم تصدير نسخة CSV لـ $it منتج") }
                    .onFailure { notice("تعذر تصدير النسخة الاحتياطية: ${it.localizedMessage}") }
            }
        }
    }

    private fun showImportPreview(preview: ImportPreview) {
        val sample = preview.rows.take(5).joinToString("\n") {
            "• ${it.name.ifBlank { "دون اسم" }}  —  ${it.barcode.ifBlank { "دون باركود" }}  —  ${it.expiry.ifBlank { "دون تاريخ" }}"
        }
        AlertDialog.Builder(this)
            .setTitle("معاينة الاستيراد")
            .setMessage(
                "منتجات مكتشفة: ${preview.rows.size}\n" +
                    "صفوف تم تجاهلها: ${preview.ignoredRows}\n" +
                    "الحقول: ${preview.detectedFields.joinToString()}\n\n" +
                    "أمثلة:\n$sample\n\n" +
                    preview.warnings.take(7).joinToString("\n") +
                    "\n\nتنبيه: حفظ المنتجات ذات الباركود المتكرر سيحدّث السجلات الموجودة. راجع البيانات قبل المتابعة."
            )
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("استيراد بعد المراجعة") { _, _ -> saveImport(preview) }
            .show()
    }

    private fun saveImport(preview: ImportPreview) {
        if (preview.rows.isEmpty()) { notice("لا توجد بيانات قابلة للاستيراد") ; return }
        worker.execute {
            var saved = 0; var rejected = 0
            for (product in preview.rows) {
                if (runCatching { store.save(product) }.isSuccess) saved++ else rejected++
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("نتيجة الاستيراد")
                    .setMessage("تم حفظ $saved منتجًا.\nتعذر حفظ $rejected سجلًا بسبب بيانات غير صحيحة أو متعارضة.\nيمكنك إصلاحها في الملف ثم إعادة الاستيراد.")
                    .setPositiveButton("عرض المنتجات") { _, _ -> show("products") }.show()
            }
        }
    }

    private fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        speech.close()
        store.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
