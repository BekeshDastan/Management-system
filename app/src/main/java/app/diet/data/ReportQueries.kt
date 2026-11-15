package app.diet.data

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import app.diet.domain.DailyGoal
import app.diet.domain.DatePeriod
import app.diet.domain.DayStatus
import app.diet.domain.DomainDates
import app.diet.domain.Nutrients
import app.diet.domain.NutritionAccumulator
import app.diet.domain.NutritionDay
import app.diet.domain.NutritionSummary
import app.diet.domain.Rational
import app.diet.domain.SleepAccumulator
import app.diet.domain.SleepMeasurement
import app.diet.domain.SleepSummary
import app.diet.domain.WeightAccumulator
import app.diet.domain.WeightMeasurement
import app.diet.domain.WeightSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ReportBundle(
    val period: DatePeriod,
    val nutrition: NutritionSummary,
    val weight: WeightSummary,
    val sleep: SleepSummary,
    val series: List<ReportSeriesPoint>,
    val topFoods: List<FrequentFood>,
    val moments: MomentSummary = MomentSummary(),
    val recordedDays: Long = 0,
)

data class MomentSummary(val photoCount: Long = 0, val noteCount: Long = 0, val moodCounts: Map<String, Long> = emptyMap(), val recordedDays: Long = 0)

/** An exact one-day point or an explicitly labelled date-range bucket, never a saved fact. */
data class ReportSeriesPoint(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val nutritionMean: Nutrients?,
    val nutritionCompleteDays: Int,
    val partialNutritionMean: Nutrients?,
    val nutritionPartialDays: Int,
    val weightMeanKg: Rational?,
    val weightDays: Int,
    val sleepMeanMinutes: Rational?,
    val sleepDays: Int,
    val goalMean: Nutrients?,
    val goalDays: Int,
    val momentDays: Long = 0,
    val recordedDays: Long = 0,
) {
    val date: LocalDate get() = start
    val calendarDays: Long get() = ChronoUnit.DAYS.between(start, endInclusive) + 1
    val aggregated: Boolean get() = start != endInclusive
    val hasNutritionGaps: Boolean get() = nutritionCompleteDays.toLong() < calendarDays
    val hasWeightGaps: Boolean get() = weightDays.toLong() < calendarDays
    val hasSleepGaps: Boolean get() = sleepDays.toLong() < calendarDays
}

data class ReportDay(
    val date: LocalDate,
    val food: NutritionDay,
    val weightCentiKg: Long?,
    val sleepMinutes: Int?,
    val goal: Nutrients?,
    val moment: ReportMomentStat? = null,
)

data class ReportDayPage(val period: DatePeriod, val offset: Long, val totalDays: Long, val days: List<ReportDay>, val hasMore: Boolean)

/** One Room read snapshot, 250-row cursors, one current food day and <=600 plot buckets. */
object ReportQueries {
    private const val CHUNK = 250
    private const val MAX_PLOT_BUCKETS = 600
    private const val DEADLINE_MS = 10_000L

    suspend fun read(database: DietDatabase, start: LocalDate, endInclusive: LocalDate): ReportBundle {
        val period = DatePeriod(start, endInclusive)
        return withContext(Dispatchers.IO) {
            withTimeout(DEADLINE_MS) {
                val job = currentCoroutineContext()[Job]
                database.useReaderConnection { connection -> connection.deferredTransaction {
                    aggregate(database.dao(), period, MAX_PLOT_BUCKETS, job, collectExact = false).bundle
                } }
            }
        }
    }

    /** Calendar pagination keeps every precise day reachable even when the plot is aggregated. */
    suspend fun pageReportDays(database: DietDatabase, start: LocalDate, endInclusive: LocalDate, offset: Long = 0, limit: Int = 40): ReportDayPage {
        val period = DatePeriod(start, endInclusive)
        require(offset >= 0 && limit in 1..50) { "Некорректная страница отчёта" }
        if (offset >= period.dayCount) return ReportDayPage(period, offset, period.dayCount, emptyList(), false)
        val pageStart = period.start.plusDays(offset)
        val pageEnd = minOf(period.endInclusive, pageStart.plusDays(limit.toLong() - 1))
        val pagePeriod = DatePeriod(pageStart, pageEnd)
        return withContext(Dispatchers.IO) {
            withTimeout(DEADLINE_MS) {
                val job = currentCoroutineContext()[Job]
                database.useReaderConnection { connection -> connection.deferredTransaction {
                    val result = aggregate(database.dao(), pagePeriod, limit, job, collectExact = true)
                    val rows = result.bundle.series.map { point ->
                        val saved = result.exactDays.getValue(point.start)
                        saved.copy(goal = point.goalMean)
                    }
                    ReportDayPage(period, offset, period.dayCount, rows, offset + rows.size < period.dayCount)
                } }
            }
        }
    }

    private data class AggregateResult(val bundle: ReportBundle, val exactDays: Map<LocalDate, ReportDay>)

    private suspend fun aggregate(dao: DietDao, period: DatePeriod, maxBuckets: Int, job: Job?, collectExact: Boolean): AggregateResult {
        val start = period.start.toString()
        val end = period.endInclusive.toString()
        val cancelled = { job?.isActive == false }
        val foodSummary = NutritionAccumulator(period, cancelled)
        val weightSummary = WeightAccumulator(period, cancelled)
        val sleepSummary = SleepAccumulator(period, cancelled)
        val width = (period.dayCount + maxBuckets - 1) / maxBuckets
        val count = ((period.dayCount + width - 1) / width).toInt()
        val buckets = List(count) { index ->
            val first = period.start.plusDays(index * width)
            Bucket(DatePeriod(first, minOf(period.endInclusive, first.plusDays(width - 1))), cancelled)
        }
        val exact = if (collectExact) HashMap<LocalDate, ReportDay>() else null
        val entries = Cursor { offset, limit -> dao.entriesPage(start, end, offset, limit) }
        val confirmations = Cursor { offset, limit -> dao.confirmationsPage(start, end, offset, limit) }
        val weights = Cursor { offset, limit -> dao.weightsPage(start, end, offset, limit) }
        val sleeps = Cursor { offset, limit -> dao.sleepsPage(start, end, offset, limit) }
        val goals = Cursor { offset, limit -> dao.goalsPageForPeriod(start, end, offset, limit) }
        val moments = Cursor { offset, limit -> dao.momentStatsPage(start, end, offset, limit) }
        var photoCount = 0L
        var noteCount = 0L
        var momentDayCount = 0L
        var recordedDayCount = 0L
        val moodCounts = linkedMapOf("great" to 0L, "good" to 0L, "neutral" to 0L, "tired" to 0L)
        var effectiveGoal: DailyGoal? = null

        fun bucketIndex(date: LocalDate) = (ChronoUnit.DAYS.between(period.start, date) / width).toInt()

        fun goalInterval(goal: DailyGoal, until: LocalDate) {
            var first = maxOf(period.start, goal.effectiveFrom)
            val last = minOf(period.endInclusive, until)
            while (first <= last) {
                job?.ensureActive()
                val bucket = buckets[bucketIndex(first)]
                val segmentEnd = minOf(last, bucket.period.endInclusive)
                val days = ChronoUnit.DAYS.between(first, segmentEnd) + 1
                bucket.goalSum += goal.nutrients * Rational.of(days)
                bucket.goalDays = Math.addExact(bucket.goalDays, days.toInt())
                if (segmentEnd == last) break
                first = segmentEnd.plusDays(1)
            }
        }

        suspend fun takeGoal() {
            val row = checkNotNull(goals.take())
            val date = DomainDates.parse(row.startDate)
            val old = effectiveGoal
            require(old == null || date > old.effectiveFrom) { "Цели отчёта повторены или не упорядочены" }
            row.nutrients.validate(goal = true)
            if (old != null) goalInterval(old, date.minusDays(1))
            effectiveGoal = DailyGoal(date, row.nutrients.toDomain())
        }

        var previousDate: LocalDate? = null
        while (true) {
            job?.ensureActive()
            val key = listOfNotNull(entries.peek()?.localDate, confirmations.peek()?.localDate, weights.peek()?.localDate, sleeps.peek()?.localDate, moments.peek()?.localDate).minOrNull() ?: break
            val date = DomainDates.parse(key)
            require(date in period && (previousDate == null || date > previousDate)) { "Повреждённый порядок дат отчёта" }
            previousDate = date
            while (goals.peek()?.startDate?.let { it <= key } == true) takeGoal()
            var total = Nutrients.ZERO
            var rowCount = 0
            while (entries.peek()?.localDate == key) {
                job?.ensureActive()
                total += checkNotNull(entries.take()).nutrients()
                rowCount = Math.addExact(rowCount, 1)
            }
            val confirmed = confirmations.peek()?.localDate == key
            if (confirmed) confirmations.take()
            val food = NutritionDay(date, total, rowCount, confirmed, confirmed && rowCount == 0)
            val bucket = buckets[bucketIndex(date)]
            foodSummary.add(food, effectiveGoal)
            bucket.food.add(food, effectiveGoal)
            val weight = if (weights.peek()?.localDate == key) checkNotNull(weights.take()).centiKg.toLong() else null
            weight?.let {
                val row = WeightMeasurement(date, it)
                weightSummary.add(row)
                bucket.weight.add(row)
            }
            val sleep = if (sleeps.peek()?.localDate == key) checkNotNull(sleeps.take()).minutes else null
            sleep?.let {
                val row = SleepMeasurement(date, it)
                sleepSummary.add(row)
                bucket.sleep.add(row)
            }
            val moment = if (moments.peek()?.localDate == key) checkNotNull(moments.take()) else null
            val hasMoment = moment != null && (moment.mood != null || moment.hasNote || moment.photoCount > 0)
            if (moment != null) {
                require(moment.photoCount in 0L..4L && (moment.mood == null || moment.mood in moodCounts)) { "Повреждены данные личного момента" }
            }
            if (hasMoment) {
                checkNotNull(moment)
                photoCount = Math.addExact(photoCount, moment.photoCount)
                if (moment.hasNote) noteCount = Math.addExact(noteCount, 1L)
                moment.mood?.let { mood -> moodCounts[mood] = Math.addExact(moodCounts.getValue(mood), 1L) }
                momentDayCount = Math.addExact(momentDayCount, 1L)
                bucket.momentDays = Math.addExact(bucket.momentDays, 1L)
            }
            if (food.status != DayStatus.MISSING || weight != null || sleep != null || hasMoment) {
                recordedDayCount = Math.addExact(recordedDayCount, 1L)
                bucket.recordedDays = Math.addExact(bucket.recordedDays, 1L)
            }
            exact?.put(date, ReportDay(date, food, weight, sleep, null, moment))
        }
        // Goal context also exists on dates with no actual data; fold intervals, not every date.
        while (goals.peek() != null) takeGoal()
        effectiveGoal?.let { goalInterval(it, period.endInclusive) }
        val points = buckets.map { bucket ->
            job?.ensureActive()
            val nutrition = bucket.food.summary()
            val weight = bucket.weight.summary()
            val sleep = bucket.sleep.summary()
            ReportSeriesPoint(bucket.period.start, bucket.period.endInclusive, nutrition.mean, nutrition.n,
                bucket.food.partialMean, nutrition.partialDays, weight.meanKg, weight.n, sleep.meanMinutes, sleep.n,
                if (bucket.goalDays == 0) null else bucket.goalSum / Rational.of(bucket.goalDays.toLong()), bucket.goalDays, bucket.momentDays, bucket.recordedDays)
        }
        if (exact != null) {
            require(period.dayCount <= 50) { "Точный список дней запрашивается только страницей" }
            points.forEach { point -> exact.putIfAbsent(point.start, ReportDay(point.start, NutritionDay(point.start), null, null, null)) }
        }
        val bundle = ReportBundle(period, foodSummary.summary(), weightSummary.summary(), sleepSummary.summary(), points, dao.frequentFoods(start, end, 5), MomentSummary(photoCount, noteCount, moodCounts.toMap(), momentDayCount), recordedDayCount)
        return AggregateResult(bundle, exact ?: emptyMap())
    }

    private class Bucket(val period: DatePeriod, cancelled: () -> Boolean) {
        val food = NutritionAccumulator(period, cancelled)
        val weight = WeightAccumulator(period, cancelled)
        val sleep = SleepAccumulator(period, cancelled)
        var goalSum = Nutrients.ZERO
        var goalDays = 0
        var momentDays = 0L
        var recordedDays = 0L
    }

    private class Cursor<T>(private val load: suspend (Long, Int) -> List<T>) {
        private var rows: List<T> = emptyList()
        private var position = 0
        private var offset = 0L
        private var exhausted = false

        suspend fun peek(): T? {
            if (position == rows.size && !exhausted) {
                rows = load(offset, CHUNK)
                require(rows.size <= CHUNK) { "Страница данных превышает бюджет" }
                position = 0
                offset = Math.addExact(offset, rows.size.toLong())
                exhausted = rows.size < CHUNK
            }
            return rows.getOrNull(position)
        }

        suspend fun take(): T? = peek()?.also { position++ }
    }
}
