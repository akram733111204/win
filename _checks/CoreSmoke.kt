import com.rukn.core.*

fun main() {
    val cases = listOf(
        "غير سنة الانتهاء الى 2027" to VoiceCommand(VoiceField.EXPIRY_DATE,VoiceAction.SET_YEAR,"2027"),
        "تاريخ الانتهاء 2028-02-29" to VoiceCommand(VoiceField.EXPIRY_DATE,VoiceAction.SET,"2028-02-29"),
        "كمية 15 حبة" to VoiceCommand(VoiceField.QUANTITY,VoiceAction.SET,"15")
    )
    for ((phrase,expected) in cases) {
        val actual = VoiceCommandEngine.interpret(phrase)
        println("phrase='$phrase' commands=${actual.commands} warnings=${actual.warnings}")
        require(expected in actual.commands) { "Mismatch: $phrase" }
    }
    val base=mapOf(VoiceField.EXPIRY_DATE to "2028-02-29")
    val interpreted=VoiceCommandEngine.interpret("غير سنة الانتهاء الى 2027")
    val applied=VoiceCommandEngine.apply(base,interpreted)
    println("applied=$applied")
    require(applied.values[VoiceField.EXPIRY_DATE]=="2028-02-29")
    require(applied.warnings.isNotEmpty())
    val valid=VoiceCommandEngine.apply(base, VoiceCommandEngine.interpret("غير سنة الانتهاء الى 2032"))
    require(valid.values[VoiceField.EXPIRY_DATE]=="2032-02-29") { "Leap year change failed $valid" }
    println("CORE_SMOKE_OK")
}
