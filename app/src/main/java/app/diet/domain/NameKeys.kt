package app.diet.domain

/** Fixed Unicode 15.1 rules, independent of Android's installed ICU or the device locale. */
object NameKeys {
    const val textKeyVersion = 1
    const val unicodeVersion = "15.1.0"

    fun normalize(value: String): String {
        TextRules.validateUnicode(value)
        val canonical = Unicode15Nfc.normalize(TextRules.trimWhiteSpace(value))
        val folded = buildString {
            var offset = 0
            while (offset < canonical.length) {
                val cp = canonical.codePointAt(offset)
                val replacement = Unicode15Data.fold[cp]
                if (replacement == null) appendCodePoint(cp) else replacement.forEach { appendCodePoint(it) }
                offset += Character.charCount(cp)
            }
        }
        return Unicode15Nfc.normalize(folded)
    }

    fun codePointCount(value: String): Int = TextRules.codePointCount(value)
}

object TextRules {
    fun codePointCount(value: String): Int {
        validateUnicode(value)
        return value.codePointCount(0, value.length)
    }

    fun validateUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            require(char != '\u0000') { "Текст содержит недопустимый нулевой символ" }
            when {
                Character.isHighSurrogate(char) -> {
                    require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) { "Текст содержит повреждённый Unicode" }
                    index += 2
                }
                Character.isLowSurrogate(char) -> throw IllegalArgumentException("Текст содержит повреждённый Unicode")
                else -> index++
            }
        }
    }

    fun trimWhiteSpace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val cp = value.codePointAt(start)
            if (!isWhiteSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = value.codePointBefore(end)
            if (!isWhiteSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return value.substring(start, end)
    }

    fun name(value: String, maxCodePoints: Int = 80): String {
        val result = optionalName(value, maxCodePoints)
        require(result.isNotEmpty()) { "Укажите название" }
        return result
    }

    fun optionalName(value: String, maxCodePoints: Int = 80): String {
        validateUnicode(value)
        return validatedNfc(trimWhiteSpace(value), maxCodePoints)
    }

    fun note(value: String): String {
        validateUnicode(value)
        return validatedNfc(value.replace("\r\n", "\n").replace('\r', '\n'), 500)
    }

    fun caption(value: String): String {
        validateUnicode(value)
        return validatedNfc(value, 80)
    }

    fun searchQuery(value: String): String = optionalName(value, 100)

    private fun validatedNfc(value: String, maximum: Int): String {
        require(maximum in 1..500) { "Некорректный лимит текста" }
        // Unicode 15.1 canonical decomposition has at most four code points per scalar.
        // A longer raw string cannot compose down to a valid field; avoid expensive work.
        require(value.codePointCount(0, value.length) <= maximum * 4) { "Допустимо не больше $maximum знаков" }
        val normalized = Unicode15Nfc.normalize(value)
        require(normalized.codePointCount(0, normalized.length) <= maximum) { "Допустимо не больше $maximum знаков" }
        return normalized
    }

    private fun isWhiteSpace(cp: Int): Boolean = cp in 0x0009..0x000D || cp == 0x0020 || cp == 0x0085 || cp == 0x00A0 || cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000
}

internal object Unicode15Nfc {
    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    fun normalize(value: String): String {
        if (value.isEmpty()) return value
        val decomposed = ArrayList<Int>(value.length)
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            decompose(cp, decomposed)
            offset += Character.charCount(cp)
        }
        // Stable order of each non-starter run; equal combining classes keep their order.
        var start = 0
        while (start < decomposed.size) {
            if (combiningClass(decomposed[start]) == 0) start++
            var end = start
            while (end < decomposed.size && combiningClass(decomposed[end]) != 0) end++
            if (end - start > 1) decomposed.subList(start, end).sortWith(compareBy(::combiningClass))
            start = end
        }
        val composed = ArrayList<Int>(decomposed.size)
        var starterPosition = 0
        var starter = decomposed.first()
        var previousClass = combiningClass(starter)
        composed += starter
        for (index in 1 until decomposed.size) {
            val cp = decomposed[index]
            val currentClass = combiningClass(cp)
            val composite = compose(starter, cp)
            if (composite != null && (previousClass == 0 || previousClass < currentClass)) {
                composed[starterPosition] = composite
                starter = composite
            } else {
                if (currentClass == 0) {
                    starterPosition = composed.size
                    starter = cp
                }
                composed += cp
                previousClass = currentClass
            }
        }
        return buildString { composed.forEach { appendCodePoint(it) } }
    }

    private fun decompose(cp: Int, result: MutableList<Int>) {
        val sIndex = cp - S_BASE
        if (sIndex in 0 until S_COUNT) {
            result += L_BASE + sIndex / N_COUNT
            result += V_BASE + (sIndex % N_COUNT) / T_COUNT
            val tail = sIndex % T_COUNT
            if (tail != 0) result += T_BASE + tail
            return
        }
        val mapping = Unicode15Data.decomposition[cp]
        if (mapping == null) result += cp else mapping.forEach { decompose(it, result) }
    }

    private fun combiningClass(cp: Int): Int = Unicode15Data.combiningClass[cp] ?: 0

    private fun compose(first: Int, second: Int): Int? {
        val lIndex = first - L_BASE
        val vIndex = second - V_BASE
        if (lIndex in 0 until L_COUNT && vIndex in 0 until V_COUNT) return S_BASE + (lIndex * V_COUNT + vIndex) * T_COUNT
        val sIndex = first - S_BASE
        val tIndex = second - T_BASE
        if (sIndex in 0 until S_COUNT && sIndex % T_COUNT == 0 && tIndex in 1 until T_COUNT) return first + tIndex
        return Unicode15Data.composition[(first.toLong() shl 21) or second.toLong()]
    }
}
