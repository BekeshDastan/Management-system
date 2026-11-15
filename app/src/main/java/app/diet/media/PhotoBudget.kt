package app.diet.media

data class PhotoBudget(val total: Long, val cache: Long, val codecReserve: Long, val io: Long = 262_144L) {
    init { require(total >= 8 * MIB) { "Недостаточно памяти для обработки фото" } }
    fun fits(width: Int, height: Int, live: Long): Boolean = Math.addExact(Math.multiplyExact(12L, Math.multiplyExact(width.toLong(), height.toLong())), Math.addExact(codecReserve + io, live)) <= total
    fun target(width: Int, height: Int, profile: Int): Pair<Int, Int> {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 64_000_000)
        val longest = maxOf(width, height); val side = minOf(profile, longest)
        return maxOf(1, width.toLong() * side / longest).toInt() to maxOf(1, height.toLong() * side / longest).toInt()
    }
    fun candidates(width: Int, height: Int, live: Long, maxLong: Int = 2048): List<Pair<Int, Int>> = PROFILES.map { target(width, height, minOf(it, maxLong)) }.distinct().filter { fits(it.first, it.second, live) }
    companion object {
        const val MIB = 1_048_576L
        val PROFILES = listOf(2048, 1536, 1024, 768, 512, 256)
        fun forMemoryClass(memoryClass: Int, lowRam: Boolean): PhotoBudget {
            val mb = minOf(80, memoryClass / 4, if (lowRam) 24 else Int.MAX_VALUE)
            val b = mb * MIB
            return PhotoBudget(b, minOf(8 * MIB, b / 8), minOf(16 * MIB, b / 4))
        }
    }
}
