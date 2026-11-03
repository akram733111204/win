package com.rukn.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Strict, deterministic validation layer for both local Arabic parsing and cloud-generated commands.
 * Transcription is untrusted. No command changes persisted records until human confirmation.
 */
enum class VoiceField {
    PRODUCTION_DATE, EXPIRY_DATE, QUANTITY, UNIT, LOT, LOCATION, AISLE, SHELF,
    PRODUCT_NAME_AR, PRODUCT_NAME_EN, BRAND, SIZE, CATEGORY, UNITS_PER_CARTON,
    PRODUCT_NOTES, BATCH_NOTES, BARCODE, EXPIRY_KIND, BATCH_STATUS
}
enum class VoiceAction { SET, CLEAR, ADD, SUBTRACT, SET_DAY, SET_MONTH, SET_YEAR }
data class VoiceCommand(val field:VoiceField,val action:VoiceAction=VoiceAction.SET,val value:String="")
data class VoiceResult(val commands:List<VoiceCommand>,val warnings:List<String>,val source:String="local") {
    val isEmpty:Boolean get()=commands.isEmpty()
}
data class VoiceApplyResult(val values:Map<VoiceField,String>,val warnings:List<String>,val changed:Set<VoiceField>)

object ArabicVoice {
    fun normalized(s:String):String {
        val ar="٠١٢٣٤٥٦٧٨٩"
        val fa="۰۱۲۳۴۵۶۷۸۹"
        return s.lowercase(Locale.ROOT).map{ch->
            when {
                ch in ar -> '0'+ar.indexOf(ch)
                ch in fa -> '0'+fa.indexOf(ch)
                ch=='أ'||ch=='إ'||ch=='آ' -> 'ا'
                ch=='ى' -> 'ي'
                ch=='ة' -> 'ه'
                ch=='ـ' -> ' '
                ch in '\u064B'..'\u065F' -> ' '
                else->ch
            }
        }.joinToString("").replace(Regex("\\s+")," ").trim()
    }
    private val simple=mapOf(
        "صفر" to 0,"واحد" to 1,"احد" to 1,"اولي" to 1,"الاول" to 1,"اول" to 1,
        "اثنين" to 2,"اثنان" to 2,"اثنا" to 2,"اثني" to 2,"اتنين" to 2,"الثاني" to 2,"ثاني" to 2,
        "ثلاثه" to 3,"ثلاث" to 3,"الثالث" to 3,"ثالث" to 3,
        "اربعه" to 4,"اربع" to 4,"الرابع" to 4,"رابع" to 4,
        "خمسه" to 5,"خمس" to 5,"الخامس" to 5,"خامس" to 5,
        "سته" to 6,"ست" to 6,"السادس" to 6,"سادس" to 6,
        "سبعه" to 7,"سبع" to 7,"السابع" to 7,"سابع" to 7,
        "ثمانيه" to 8,"ثمان" to 8,"الثامن" to 8,"ثامن" to 8,
        "تسعه" to 9,"تسع" to 9,"التاسع" to 9,"تاسع" to 9,
        "عشره" to 10,"عشر" to 10,"العاشر" to 10,"عاشر" to 10,
        "احدعشر" to 11,"اثناعشر" to 12,"اثنعشر" to 12,
        "عشرين" to 20,"العشرين" to 20,"ثلاثين" to 30,"اربعين" to 40,
        "خمسين" to 50,"ستين" to 60,"سبعين" to 70,"ثمانين" to 80,"تسعين" to 90,
        "ميه" to 100,"مئه" to 100,"مائه" to 100,"الف" to 1000,"الفين" to 2000,"الفان" to 2000,
        "مئتين" to 200,"مئتان" to 200,"مايه" to 100
    )
    private val skip=setOf("و","ال","من","في","يوم","اليوم","شهر","سنه","سنة","عام","الى","إلى","سنه","الي","عدد","هو","رقم")
    /** Returns null rather than inventing a number from an unfamiliar phrase. */
    fun number(s:String):Int? {
        val n=normalized(s).replace(Regex("[،,:;.]")," ").trim()
        n.toIntOrNull()?.let{return it}
        val pieces=n.split(Regex("\\s+")).filter{it.isNotBlank()}.flatMap{piece->
            // Arabic conjunction is often glued to the next number: "وعشرين", "وثمانية".
            val sub=if(piece.startsWith("و") && piece.length>2 && simple.containsKey(piece.drop(1)))piece.drop(1) else piece
            listOf(sub)
        }
        if(pieces.isEmpty())return null
        var total=0;var valid=0
        for(p in pieces){
            if(p in skip)continue
            val v=simple[p] ?: simple[p.removePrefix("ال")] ?: return null
            total+=v;valid++
        }
        return if(valid>0)total else null
    }
}

/** Flexible local fallback: intentionally conservative where dates can affect food safety. */
object VoiceCommandEngine {
    private data class Marker(val field:VoiceField,val match:MatchResult)
    private val labelPatterns = linkedMapOf(
        VoiceField.PRODUCTION_DATE to Regex("(?:تاريخ\\s*)?(?:الانتاج|انتاج|تاريخ\\s*الصنع|التصنيع|صنع|mfg|production)"),
        VoiceField.EXPIRY_DATE to Regex("(?:تاريخ\\s*)?(?:الانتهاء|انتهاء|الصلاحيه|الصلاحيه|expiry|expires?|exp)"),
        VoiceField.UNITS_PER_CARTON to Regex("(?:عدد\\s*)?(?:الحبات|القطع|العبوات)\\s*(?:في|بال)\\s*الكرتون"),
        VoiceField.PRODUCT_NAME_AR to Regex("(?:اسم\\s*(?:المنتج|الصنف)|المنتج\\s*اسمه|اسم\\s*البضاعه)"),
        VoiceField.PRODUCT_NAME_EN to Regex("(?:الاسم\\s*الانجليزي|english\\s*name)"),
        VoiceField.PRODUCT_NOTES to Regex("(?:ملاحظات\\s*المنتج|ملاحظه\\s*المنتج)"),
        VoiceField.BATCH_NOTES to Regex("(?:ملاحظات\\s*التشغيله|الملاحظه|ملاحظه|ملاحظات)"),
        VoiceField.BRAND to Regex("(?:العلامه\\s*التجاريه|الماركه|البراند|brand)"),
        VoiceField.CATEGORY to Regex("(?:القسم|قسم|التصنيف)"),
        VoiceField.SIZE to Regex("(?:الحجم|الوزن|حجم\\s*العبوه)"),
        VoiceField.LOT to Regex("(?:رقم\\s*)?(?:التشغيله|تشغيله|تشغليه|lot|batch)"),
        VoiceField.QUANTITY to Regex("(?:يوجد\\s*(?:منه|منها)|عندي|عندنا|الكميه|كميه|العدد|الموجود\\s*(?:منه|منها)?)"),
        VoiceField.AISLE to Regex("(?:رقم\\s*)?(?:الممر|ممر)"),
        VoiceField.SHELF to Regex("(?:رقم\\s*)?(?:الرف|رف)"),
        VoiceField.LOCATION to Regex("(?:مكانه|مكانها|المكان|موقعه|موقعها|الموقع)"),
        VoiceField.UNIT to Regex("(?:وحده\\s*القياس|الوحده)"),
        VoiceField.BARCODE to Regex("(?:الباركود|باركود|barcode)"),
        VoiceField.EXPIRY_KIND to Regex("(?:نوع\\s*التاريخ|نوع\\s*الصلاحيه)"),
        VoiceField.BATCH_STATUS to Regex("(?:حاله\\s*التشغيله|حاله\\s*الدفعة)"),
    )
    private val monthNames=mapOf(
        "يناير" to 1,"جانفي" to 1,"فبراير" to 2,"فيفري" to 2,"مارس" to 3,"ابريل" to 4,"نيسان" to 4,
        "مايو" to 5,"ماي" to 5,"يونيو" to 6,"حزيران" to 6,"يوليو" to 7,"تموز" to 7,"اغسطس" to 8,"اوت" to 8,
        "سبتمبر" to 9,"شتنبر" to 9,"اكتوبر" to 10,"تشرين" to 10,"نوفمبر" to 11,"ديسمبر" to 12,"دجنبر" to 12,
        "jan" to 1,"feb" to 2,"mar" to 3,"apr" to 4,"may" to 5,"jun" to 6,"jul" to 7,"aug" to 8,
        "sep" to 9,"oct" to 10,"nov" to 11,"dec" to 12
    )
    private val yearLabel=Regex("(?:سن[هه]|سنه|عام|السنه|لسنه|year)\\s+(.+)$")
    private val dayLabel=Regex("(?:يوم|اليوم|بتاريخ)\\s+(.+?)(?=\\s+(?:من\\s+شهر|من|في\\s+شهر|شهر|سن[هه]|سنه|عام|year)(?=\\s|$)|$)")
    private val monthLabel=Regex("(?:في\\s+)?(?:شهر|الشهر)\\s+(.+?)(?=\\s+(?:سن[هه]|سنه|عام|السنه|year)(?=\\s|$)|$)")
    private val numericDate=Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?(?!\\d)|(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})(?!\\d)")
    private val nounQuantity=Regex("([\\p{L}0-9]+(?:\\s+(?:و?[\\p{L}0-9]+)){0,3}?)\\s*(?:كرتون|كراتين|كراتون|كرتونه|حبات|حبه|حبتين|قطع|قطعه|عبوات|عبوه)")
    private fun norm(s:String)=ArabicVoice.normalized(s)
    private fun cleanValue(s:String)=s.trim(' ',':','-','،','.') .removePrefix("هو ").removePrefix("هي ").removePrefix("الى ").removePrefix("الي ").removePrefix("لـ").trim()
    private fun namedMonth(text:String):Int?=monthNames.entries.firstOrNull{Regex("(?<![\\p{L}])${Regex.escape(it.key)}(?![\\p{L}])").containsMatchIn(text)}?.value
    private fun shortYear(n:Int,warnings:MutableList<String>):Int {
        return if(n in 0..99){warnings+="سنة '$n' مختصرة؛ فُسرت إلى ${2000+n}. أكد السنة قبل الحفظ.";2000+n} else n
    }
    private fun num(s:String):Int?=ArabicVoice.number(s.replace("من شهر"," ").trim())
    private fun readDate(original:String,warnings:MutableList<String>,prefix:String,field:VoiceField):VoiceCommand? {
        val s=norm(cleanValue(original))
        val found=numericDate.find(s)
        if(found!=null){
            return try {
                val g=found.groupValues
                if(g[1].isNotBlank()){
                    val y=g[1].toInt();val m=g[2].toInt();val d=g[3].toIntOrNull()
                    if(d==null) VoiceCommand(field,value=YearMonth.of(y,m).toString())
                    else VoiceCommand(field,value=LocalDate.of(y,m,d).toString())
                } else {
                    val d=g[4].toInt();val m=g[5].toInt();val y=shortYear(g[6].toInt(),warnings)
                    if(d in 1..12 && m in 1..12 && d!=m) warnings+="التاريخ ${found.value} قد يلتبس بين اليوم والشهر؛ تحقق من الصيغة المطبوعة."
                    VoiceCommand(field,value=LocalDate.of(y,m,d).toString())
                }
            } catch(_:Exception){warnings+="تاريخ غير صحيح: ${found.value}";null}
        }
        val yr=yearLabel.find(s)?.groupValues?.get(1)?.let{raw->
            // Full spoken year e.g. "الفين وثمانيه وعشرين".
            num(raw.substringBefore(" ").takeIf{it.toIntOrNull()!=null}?:raw)
        }
        val month=monthLabel.find(s)?.groupValues?.get(1)?.let{namedMonth(it)?:num(it)}
            ?:namedMonth(s)
            ?:Regex("(?<!\\d)(?:شهر\\s*)?(\\d{1,2})(?!\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf{it in 1..12 && s.contains("شهر")}
        val day=dayLabel.find(s)?.groupValues?.get(1)?.let{num(it)}
            ?:Regex("^([\\p{L}\\s\\d]+?)\\s+من\\s+(?:شهر\\s*)?(?:[\\p{L}]+|\\d{1,2})").find(s)?.groupValues?.get(1)?.let{num(it)}
        val contextPrefix=norm(prefix.takeLast(40))
        val editing=contextPrefix.contains(Regex("(?:عدل|غير|صحح|بدل|خلي)"))
        val partHint=when {
            contextPrefix.endsWith("شهر") || s.startsWith("الشهر ")->VoiceAction.SET_MONTH
            contextPrefix.endsWith("يوم") || s.startsWith("اليوم ")->VoiceAction.SET_DAY
            contextPrefix.endsWith("سنه") || contextPrefix.endsWith("عام")->VoiceAction.SET_YEAR
            else->null
        }
        if(editing && partHint!=null){
            val part=when(partHint){VoiceAction.SET_MONTH->month?:namedMonth(s)?:num(s);VoiceAction.SET_DAY->day?:num(s);else->yr?:num(s)}
            if(part==null){warnings+="لم أفهم الجزء الذي تريد تغييره من التاريخ.";return null}
            return VoiceCommand(field,partHint,part.toString())
        }
        if(yr==null && month==null && day==null){
            // A correction such as "عدل شهر الانتهاء الى نوفمبر".
            if(editing){
                val onlyMonth=namedMonth(s)
                if(onlyMonth!=null)return VoiceCommand(field,VoiceAction.SET_MONTH,onlyMonth.toString())
            }
            warnings+="لم أستطع استخراج تاريخ واضح لـ${if(field==VoiceField.EXPIRY_DATE)"الانتهاء" else "الإنتاج"}.";return null
        }
        // Corrections of just the month/day/year, without the other components.
        if(editing && yr==null && day==null && month!=null)return VoiceCommand(field,VoiceAction.SET_MONTH,month.toString())
        if(editing && yr==null && month==null && day!=null)return VoiceCommand(field,VoiceAction.SET_DAY,day.toString())
        if(yr==null || month==null){warnings+="تاريخ ${if(field==VoiceField.EXPIRY_DATE)"الانتهاء" else "الإنتاج"} غير مكتمل: يلزم ذكر الشهر والسنة على الأقل.";return null}
        val year=shortYear(yr,warnings)
        return try {
            val date=if(day==null)YearMonth.of(year,month).toString() else LocalDate.of(year,month,day).toString()
            VoiceCommand(field,value=date)
        }catch(_:Exception){warnings+="تاريخ غير صالح تقويميًا: $s";null}
    }
    private fun quantityCommands(s:String,prefix:String,warnings:MutableList<String>):List<VoiceCommand>{
        val units=Regex("كراتين|كراتون|كرتونه|كرتون|حبات|حبتين|حبه|قطع|قطعه|عبوات|عبوه")
        val found=mutableListOf<Pair<Int,String>>()
        var from=0
        for(match in units.findAll(s)){
            val preceding=s.substring(from,match.range.first).trim()
                .replace(Regex("^(?:و|منه|منها|عدد|هي|هو)\\s*"),"")
                .replace(Regex("^و(?=[\\p{L}0-9])"),"")
            val possible=preceding.split(Regex("\\s+")).let{words->
                (0..words.size).mapNotNull{drop->num(words.drop(drop).joinToString(" "))}.firstOrNull()
            }
            if(possible!=null)found+=possible to match.value
            from=match.range.last+1
        }
        val cartons=found.firstOrNull{it.second in setOf("كراتين","كراتون","كرتونه","كرتون")}?.first
        val pieces=found.firstOrNull{it.second in setOf("حبات","حبتين","حبه","قطع","قطعه","عبوات","عبوه")}?.first
        val rawNum=if(cartons==null && pieces==null)num(s) else null
        if(cartons==null && pieces==null && rawNum==null){warnings+="لم أفهم كمية واضحة.";return emptyList()}
        val op=when {
            norm(prefix).contains(Regex("(?:زود|اضف|زد|زياده)"))->VoiceAction.ADD
            norm(prefix).contains(Regex("(?:انقص|اطرح|نقص|احذف)"))->VoiceAction.SUBTRACT
            else->VoiceAction.SET
        }
        return if(cartons!=null && pieces!=null){
            if(op!=VoiceAction.SET){warnings+="لم أنفذ عملية حسابية على كراتين وحبات مختلطة؛ احتجت إلى عدد وحدات الكرتون.";return emptyList()}
            listOf(VoiceCommand(VoiceField.QUANTITY,VoiceAction.SET,"$cartons كرتون + $pieces حبة"),VoiceCommand(VoiceField.UNIT,VoiceAction.SET,"مختلطة"))
        } else if(cartons!=null) listOf(VoiceCommand(VoiceField.QUANTITY,op,cartons.toString()),VoiceCommand(VoiceField.UNIT,VoiceAction.SET,"كرتون"))
        else if(pieces!=null) listOf(VoiceCommand(VoiceField.QUANTITY,op,pieces.toString()),VoiceCommand(VoiceField.UNIT,VoiceAction.SET,"حبة"))
        else listOf(VoiceCommand(VoiceField.QUANTITY,op,rawNum.toString()))
    }
    fun interpret(transcript:String):VoiceResult {
        val input=norm(transcript)
        if(input.isBlank())return VoiceResult(emptyList(),listOf("لا يوجد كلام واضح لتحليله."))
        val matches=mutableListOf<Marker>()
        for((field,regex) in labelPatterns) regex.findAll(input).forEach{matches+=Marker(field,it)}
        // Avoid overlapping matches e.g. "ملاحظات المنتج" also includes "المنتج".
        val ordered=matches.sortedWith(compareBy<Marker>{it.match.range.first}.thenByDescending{it.match.value.length})
            .fold(mutableListOf<Marker>()){acc,m->if(acc.none{m.match.range.first in it.match.range})acc.add(m);acc}
        val commands=mutableListOf<VoiceCommand>();val warnings=mutableListOf<String>()
        if(ordered.isEmpty())return VoiceResult(emptyList(),listOf("لم أتعرف على اسم الحقل. اذكر مثلًا: تاريخ الانتهاء، الكمية، الرف، الملاحظات."))
        ordered.forEachIndexed{index,item->
            val start=item.match.range.last+1
            val end=ordered.getOrNull(index+1)?.match?.range?.first?:input.length
            val content=cleanValue(input.substring(start.coerceAtMost(end),end))
            val prefix=input.substring(maxOf(0,item.match.range.first-24),item.match.range.first)
            val clear=Regex("(?:احذف|امسح|افرغ|خلي\\s*فاضي|اترك\\s*فارغ)").containsMatchIn(prefix) ||
                Regex("^(?:احذف|امسح|خليه\\s*فاضي|غير\\s*معروف)").containsMatchIn(content)
            if(clear){commands+=VoiceCommand(item.field,VoiceAction.CLEAR);return@forEachIndexed}
            when(item.field){
                VoiceField.PRODUCTION_DATE,VoiceField.EXPIRY_DATE->readDate(content,warnings,prefix,item.field)?.let{commands+=it}
                VoiceField.QUANTITY->commands+=quantityCommands(content,prefix,warnings)
                VoiceField.UNITS_PER_CARTON->num(content)?.let{commands+=VoiceCommand(item.field,value=it.toString())}
                    ?:run{warnings+="لم أفهم عدد الحبات في الكرتون."}
                VoiceField.BARCODE->{
                    val raw=content.replace(Regex("\\s+"),"")
                    if(raw.matches(Regex("[0-9A-Za-z-]{5,64}")))commands+=VoiceCommand(item.field,value=raw)
                    else warnings+="الباركود المنطوق غير واضح؛ يفضل مسحه أو إدخاله بالأرقام مباشرة."
                }
                VoiceField.EXPIRY_KIND->{
                    val v=when{content.contains("افضل") || content.contains("best")->"BEST_BEFORE"
                        content.contains("انتهاء")||content.contains("exp")->"EXPIRY";else->null}
                    if(v!=null)commands+=VoiceCommand(item.field,value=v) else warnings+="حدد نوع التاريخ: انتهاء أو الأفضل قبل."
                }
                VoiceField.BATCH_STATUS->{
                    val v=when{content.contains("معزول")->"QUARANTINED";content.contains("مسحوب")->"REMOVED"
                        content.contains("نفد")||content.contains("خلص")->"SOLD_OUT"
                        content.contains("نشط")||content.contains("متوفر")->"ACTIVE";else->null}
                    if(v!=null)commands+=VoiceCommand(item.field,value=v) else warnings+="لم أفهم حالة التشغيلة."
                }
                else->if(content.isNotBlank())commands+=VoiceCommand(item.field,value=content)
                    else warnings+="الحقل ${item.field} لم تُذكر له قيمة."
            }
        }
        return validate(VoiceResult(commands,warnings))
    }
    fun validate(result:VoiceResult):VoiceResult {
        val warnings=result.warnings.toMutableList()
        val accepted=mutableListOf<VoiceCommand>()
        result.commands.take(32).forEach{c->
            val value=c.value.trim().take(400)
            if(c.action==VoiceAction.CLEAR){accepted+=c.copy(value="");return@forEach}
            if(value.isBlank()){warnings+="قيمة فارغة لحقل ${c.field}.";return@forEach}
            if(c.field==VoiceField.EXPIRY_KIND && value !in setOf("EXPIRY","BEST_BEFORE")){
                warnings+="نوع تاريخ غير صالح: $value";return@forEach
            }
            if(c.field==VoiceField.BATCH_STATUS && value !in setOf("ACTIVE","QUARANTINED","REMOVED","SOLD_OUT")){
                warnings+="حالة تشغيلة غير صالحة: $value";return@forEach
            }
            if(c.field==VoiceField.BARCODE && !value.matches(Regex("[A-Za-z0-9-]{5,64}"))){
                warnings+="رمز باركود غير صالح؛ أدخله يدويًا أو امسحه.";return@forEach
            }
            if(c.field in setOf(VoiceField.PRODUCTION_DATE,VoiceField.EXPIRY_DATE)){
                if(c.action==VoiceAction.SET){
                    val ok=runCatching{if(value.length==7)YearMonth.parse(value) else LocalDate.parse(value)}.isSuccess
                    if(!ok){warnings+="تاريخ غير صالح: $value";return@forEach}
                } else if(c.action in setOf(VoiceAction.SET_MONTH,VoiceAction.SET_DAY,VoiceAction.SET_YEAR)){
                    val n=value.toIntOrNull()
                    if(n==null || (c.action==VoiceAction.SET_MONTH && n !in 1..12) || (c.action==VoiceAction.SET_DAY && n !in 1..31) || (c.action==VoiceAction.SET_YEAR && n !in 1900..2199)){
                        warnings+="جزء تاريخ غير صالح: $value";return@forEach
                    }
                } else {warnings+="عملية غير مسموحة على التاريخ";return@forEach}
            } else if(c.action in setOf(VoiceAction.SET_DAY,VoiceAction.SET_MONTH,VoiceAction.SET_YEAR)){
                warnings+="تغيير جزء التاريخ غير صالح للحقل ${c.field}";return@forEach
            }
            if(c.action in setOf(VoiceAction.ADD,VoiceAction.SUBTRACT) && (c.field!=VoiceField.QUANTITY || value.toBigDecimalOrNull()==null)){
                warnings+="الزيادة والنقصان يحتاجان كمية رقمية واضحة.";return@forEach
            }
            accepted+=c.copy(value=value)
        }
        if(result.commands.size>32)warnings+="تم تجاهل أوامر زائدة عن الحد الآمن."
        return VoiceResult(accepted,warnings.distinct(),result.source)
    }
    fun apply(base:Map<VoiceField,String>,result:VoiceResult):VoiceApplyResult {
        val safe=validate(result);val values=base.toMutableMap();val warnings=safe.warnings.toMutableList()
        val changed=mutableSetOf<VoiceField>()
        val numericArithmetic=safe.commands.any{it.field==VoiceField.QUANTITY && it.action in setOf(VoiceAction.ADD,VoiceAction.SUBTRACT)}
        val incomingUnit=safe.commands.lastOrNull{it.field==VoiceField.UNIT && it.action==VoiceAction.SET}?.value.orEmpty()
        val existingUnit=base[VoiceField.UNIT].orEmpty()
        val unitMismatch=numericArithmetic && incomingUnit.isNotBlank() && existingUnit.isNotBlank() &&
            ArabicVoice.normalized(incomingUnit)!=ArabicVoice.normalized(existingUnit)
        if(unitMismatch)warnings+="تعذر جمع أو طرح كميات بوحدات مختلفة ($existingUnit مقابل $incomingUnit). تحقق من معامل التحويل وأدخل الرصيد النهائي."
        for(c in safe.commands){
            if(unitMismatch && c.field in setOf(VoiceField.QUANTITY,VoiceField.UNIT))continue
            val old=values[c.field].orEmpty()
            val proposed=when(c.action){
                VoiceAction.CLEAR->""
                VoiceAction.SET->c.value
                VoiceAction.ADD,VoiceAction.SUBTRACT->{
                    val n=old.toBigDecimalOrNull();val delta=c.value.toBigDecimalOrNull()
                    if(n==null||delta==null){warnings+="تعذر تعديل كمية غير رقمية؛ لم تتغير القيمة.";continue}
                    val new=if(c.action==VoiceAction.ADD)n+delta else n-delta
                    if(new<BigDecimal.ZERO){warnings+="لا يُسمح بكمية سالبة في إدخال التشغيلة.";continue}
                    new.stripTrailingZeros().toPlainString()
                }
                VoiceAction.SET_DAY,VoiceAction.SET_MONTH,VoiceAction.SET_YEAR->{
                    if(old.isBlank()){warnings+="لا يمكن تعديل جزء من تاريخ غير موجود؛ انطق التاريخ كاملًا.";continue}
                    val date=runCatching{if(old.length==7)YearMonth.parse(old).atDay(1) else LocalDate.parse(old)}.getOrNull()
                    if(date==null){warnings+="التاريخ الحالي غير صالح ولا يمكن تعديله جزئيًا.";continue}
                    val number=c.value.toIntOrNull()?:continue
                    val modified=runCatching{when(c.action){
                        VoiceAction.SET_DAY->LocalDate.of(date.year,date.monthValue,number)
                        VoiceAction.SET_MONTH->LocalDate.of(date.year,number,date.dayOfMonth)
                        else->LocalDate.of(number,date.monthValue,date.dayOfMonth)
                    }}.getOrNull()
                    if(modified==null){warnings+="التعديل ينتج تاريخًا غير موجود في التقويم. راجع اليوم والشهر.";continue}
                    if(old.length==7 && c.action!=VoiceAction.SET_DAY)YearMonth.from(modified).toString() else modified.toString()
                }
            }
            if(c.field==VoiceField.QUANTITY && c.action==VoiceAction.SET && proposed.matches(Regex("-\\d+(?:[.]\\d+)?"))){warnings+="كمية سالبة تحتاج مراجعة.";continue}
            values[c.field]=proposed
            if(old!=proposed)changed+=c.field
        }
        val m=values[VoiceField.PRODUCTION_DATE].orEmpty();val e=values[VoiceField.EXPIRY_DATE].orEmpty()
        if(m.isNotBlank()&&e.isNotBlank()){
            val manufacture=runCatching{if(m.length==7)YearMonth.parse(m).atDay(1) else LocalDate.parse(m)}.getOrNull()
            val expiry=runCatching{if(e.length==7)YearMonth.parse(e).atEndOfMonth() else LocalDate.parse(e)}.getOrNull()
            if(manufacture!=null && expiry!=null && manufacture>expiry)warnings+="تاريخ الإنتاج بعد الانتهاء؛ يجب التصحيح قبل الحفظ."
        }
        return VoiceApplyResult(values,warnings.distinct(),changed)
    }
}
