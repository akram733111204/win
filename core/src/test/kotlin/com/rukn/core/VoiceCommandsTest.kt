package com.rukn.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceCommandsTest {
    @Test fun understandsArabicYearCorrection() {
        val actual = VoiceCommandEngine.interpret("غير سنة الانتهاء الى 2027")
        assertTrue(VoiceCommand(VoiceField.EXPIRY_DATE, VoiceAction.SET_YEAR, "2027") in actual.commands,
            "Parsed commands: ${actual.commands}; warnings: ${actual.warnings}")
    }

    @Test fun cannotCorrectDateToImpossibleDay() {
        val old = mapOf(VoiceField.EXPIRY_DATE to "2028-02-29")
        val command = VoiceCommandEngine.interpret("غير سنة الانتهاء الى 2027")
        assertEquals(VoiceCommand(VoiceField.EXPIRY_DATE, VoiceAction.SET_YEAR, "2027"), command.commands.single())
        val applied = VoiceCommandEngine.apply(old, command)
        assertEquals("2028-02-29", applied.values[VoiceField.EXPIRY_DATE])
        assertFalse(VoiceField.EXPIRY_DATE in applied.changed)
        assertTrue(applied.warnings.isNotEmpty(), "An invalid date must produce a warning")
    }

    @Test fun canCorrectLeapYearToAnotherLeapYear() {
        val old = mapOf(VoiceField.EXPIRY_DATE to "2028-02-29")
        val command = VoiceCommandEngine.interpret("غير سنة الانتهاء الى 2032")
        val applied = VoiceCommandEngine.apply(old, command)
        assertEquals("2032-02-29", applied.values[VoiceField.EXPIRY_DATE])
        assertTrue(VoiceField.EXPIRY_DATE in applied.changed)
    }

    @Test fun impossibleFullDateIsNotParsed() {
        val command = VoiceCommandEngine.interpret("تاريخ الانتهاء 2027-02-29")
        assertTrue(command.commands.isEmpty())
        assertTrue(command.warnings.isNotEmpty())
    }

    @Test fun understandsArabicDigits() {
        assertEquals(2027, ArabicVoice.number("٢٠٢٧"))
        assertEquals(23, ArabicVoice.number("ثلاثة وعشرين"))
    }

    @Test fun readsDateAndQuantity() {
        val result = VoiceCommandEngine.interpret("تاريخ الانتهاء 2027-12-30 والكمية 15 حبة")
        assertTrue(VoiceCommand(VoiceField.EXPIRY_DATE, VoiceAction.SET, "2027-12-30") in result.commands,
            "Parsed: ${result.commands}, warnings: ${result.warnings}")
        assertTrue(VoiceCommand(VoiceField.QUANTITY, VoiceAction.SET, "15") in result.commands,
            "Parsed: ${result.commands}, warnings: ${result.warnings}")
    }

    @Test fun validatesCloudGeneratedImpossibleDate() {
        val cloud = VoiceResult(listOf(VoiceCommand(VoiceField.EXPIRY_DATE, VoiceAction.SET, "2027-02-29")), emptyList(), "cloud")
        val safe = VoiceCommandEngine.validate(cloud)
        assertTrue(safe.commands.isEmpty())
        assertTrue(safe.warnings.isNotEmpty())
    }

    @Test fun noPartialEditingWithoutPriorDate() {
        val result = VoiceCommandEngine.apply(emptyMap(), VoiceResult(
            listOf(VoiceCommand(VoiceField.EXPIRY_DATE, VoiceAction.SET_YEAR, "2027")), emptyList()))
        assertEquals(null, result.values[VoiceField.EXPIRY_DATE])
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun noNegativeQuantityArithmetic() {
        val result = VoiceCommandEngine.apply(mapOf(VoiceField.QUANTITY to "5"), VoiceResult(
            listOf(VoiceCommand(VoiceField.QUANTITY, VoiceAction.SUBTRACT, "10")), emptyList()))
        assertEquals("5", result.values[VoiceField.QUANTITY])
        assertTrue(result.warnings.isNotEmpty())
    }
}
