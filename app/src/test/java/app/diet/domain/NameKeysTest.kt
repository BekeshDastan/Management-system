package app.diet.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class NameKeysTest {
    @Test fun keysAreLocaleIndependentAndDoNotTransliterateScripts() {
        assertEquals("обед", NameKeys.normalize("\u0085\u00A0 ОБЕД \u3000"))
        assertEquals(NameKeys.normalize("Straße"), NameKeys.normalize("STRASSE"))
        assertEquals(NameKeys.normalize("Й"), NameKeys.normalize("И\u0306"))
        assertNotEquals(NameKeys.normalize("A"), NameKeys.normalize("А"))
        assertEquals("i\u0307", NameKeys.normalize("İ"))
        assertEquals(1, NameKeys.textKeyVersion)
    }

    @Test fun codePointLimitsNormalizeWithoutSplittingEmoji() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(7, TextRules.codePointCount(family))
        assertEquals("Й", TextRules.name(" И\u0306 "))
        assertEquals("a".repeat(80), TextRules.name("a".repeat(80)))
        assertThrows(IllegalArgumentException::class.java) { TextRules.name("a".repeat(81)) }
        assertThrows(IllegalArgumentException::class.java) { TextRules.name("\uD800") }
        assertThrows(IllegalArgumentException::class.java) { TextRules.name("a\u0000") }
        assertEquals("", TextRules.optionalName("\u0085\u00A0"))
        assertEquals(" a\nb\nc ", TextRules.note(" a\r\nb\rc "))
        assertEquals(" a ", TextRules.caption(" a "))
        assertEquals("\u200Bx\u200B", TextRules.trimWhiteSpace("\u200Bx\u200B"))
    }

    @Test fun completeUnicode151NormalizationConformance() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/unicode15/NormalizationTest.txt.gz"))
        var cases = 0
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val body = raw.substringBefore('#').trim()
                if (body.isEmpty() || body.startsWith('@')) return@forEach
                val columns = body.split(';').take(5).map(::codePoints)
                assertEquals("NFC c1 case $cases", columns[1], Unicode15Nfc.normalize(columns[0]))
                assertEquals("NFC c2 case $cases", columns[1], Unicode15Nfc.normalize(columns[1]))
                assertEquals("NFC c3 case $cases", columns[1], Unicode15Nfc.normalize(columns[2]))
                assertEquals("NFC c4 case $cases", columns[3], Unicode15Nfc.normalize(columns[3]))
                assertEquals("NFC c5 case $cases", columns[3], Unicode15Nfc.normalize(columns[4]))
                cases++
            }
        }
        assertEquals(19_074, cases)
    }

    @Test fun completeDefaultCaseFoldingTableMatchesOfficial151Source() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/unicode15/CaseFolding.txt.gz"))
        var cases = 0
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val body = raw.substringBefore('#').trim()
                if (body.isEmpty()) return@forEach
                val columns = body.split(';').map { it.trim() }
                if (columns[1] !in listOf("C", "F")) return@forEach
                val cp = columns[0].toInt(16)
                val expected = columns[2].split(' ').filter { it.isNotBlank() }.map { it.toInt(16) }.toIntArray()
                assertArrayEquals("fold U+${columns[0]}", expected, Unicode15Data.fold[cp])
                assertEquals(Unicode15Nfc.normalize(codePoints(columns[2])), NameKeys.normalize(String(Character.toChars(cp))))
                cases++
            }
        }
        assertEquals(1_530, cases)
        assertEquals(cases, Unicode15Data.fold.size)
    }

    private fun codePoints(raw: String): String = buildString {
        raw.trim().split(' ').filter { it.isNotEmpty() }.forEach { appendCodePoint(it.toInt(16)) }
    }
}
