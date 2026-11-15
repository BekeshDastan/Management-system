package app.diet.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.diet.domain.FoodUnit
import app.diet.domain.FoodSourceKind
import app.diet.domain.NameKeys
import app.diet.domain.Nutrients
import app.diet.domain.NutritionSnapshot
import app.diet.domain.Rational
import java.math.BigInteger
import java.util.UUID
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

data class MicroNutrients(val kcal: Long, val protein: Long, val fat: Long, val carbs: Long) {
    fun toDomain() = Nutrients.fromMicros(kcal, protein, fat, carbs)
    fun validate(goal: Boolean = false) {
        require(listOf(kcal, protein, fat, carbs).all { it in 0..1_000_000_000_000L }) { "Недопустимое КБЖУ" }
        require(!goal || kcal > 0) { "Калории цели должны быть больше нуля" }
    }
}

/** Owned by each historical row. No foreign key to a shared mutable snapshot. */
data class SnapshotColumns(
    val name: String,
    val unit: String,
    val basisMicros: Long,
    val portionName: String? = null,
    val kcalNumerator: String, val kcalDenominator: String,
    val proteinNumerator: String, val proteinDenominator: String,
    val fatNumerator: String, val fatDenominator: String,
    val carbsNumerator: String, val carbsDenominator: String,
    val snapshotFormatVersion: Int = 1,
    val calcContractVersion: Int = 1,
    val sourceKind: String = "PRODUCT",
    val nameKey: String = NameKeys.normalize(name),
) {
    fun toDomain(): NutritionSnapshot {
        require(snapshotFormatVersion == 1 && calcContractVersion == 1) { "Неизвестный формат снимка" }
        fun rational(n: String, d: String): Rational {
            require(n.matches(Regex("0|[1-9][0-9]{0,127}")) && d.matches(Regex("[1-9][0-9]{0,127}"))) { "Повреждённое рациональное значение" }
            val numerator = BigInteger(n); val denominator = BigInteger(d)
            require(numerator.gcd(denominator) == BigInteger.ONE && numerator <= BigInteger.TEN.pow(30) * denominator)
            return Rational.of(numerator, denominator)
        }
        return NutritionSnapshot(name, FoodUnit.valueOf(unit), basisMicros,
            Nutrients(rational(kcalNumerator, kcalDenominator), rational(proteinNumerator, proteinDenominator), rational(fatNumerator, fatDenominator), rational(carbsNumerator, carbsDenominator)), portionName, calcContractVersion, FoodSourceKind.valueOf(sourceKind))
    }
    companion object {
        fun from(snapshot: NutritionSnapshot): SnapshotColumns {
            val n = snapshot.nutrientsPerBasis
            return SnapshotColumns(snapshot.name, snapshot.unit.name, snapshot.basisMicros, snapshot.portionName,
                n.kcal.numerator.toString(), n.kcal.denominator.toString(), n.protein.numerator.toString(), n.protein.denominator.toString(),
                n.fat.numerator.toString(), n.fat.denominator.toString(), n.carbs.numerator.toString(), n.carbs.denominator.toString(), sourceKind = snapshot.sourceKind.name)
        }
    }
}

@Entity(tableName = "local_profile", indices = [Index(value = ["slot"], unique = true)])
data class ProfileEntity(@PrimaryKey val id: String = newId(), val slot: Int = 1, val name: String = "", val age: Int? = null, val heightCentiCm: Int? = null, val initialWeightCentiKg: Int? = null, val direction: String? = null, val revision: Long = 1)

@Entity(tableName = "onboarding_state", indices = [Index(value = ["slot"], unique = true)])
data class OnboardingEntity(@PrimaryKey val id: String = newId(), val slot: Int = 1, val completed: Boolean = false, val step: String = "welcome", val draftId: String? = null, val revision: Long = 1)

@Entity(tableName = "meals", indices = [Index(value = ["nameKey"], unique = true), Index(value = ["ordinal"], unique = true)])
data class MealEntity(@PrimaryKey val id: String = newId(), val name: String, val nameKey: String, val textKeyVersion: Int = 1, val ordinal: Long, val archived: Boolean = false, val revision: Long = 1)

@Entity(tableName = "catalog_categories", indices = [Index(value = ["code"], unique = true)])
data class CategoryEntity(@PrimaryKey val id: String = newId(), val code: String, val name: String, val ordinal: Long, val revision: Long = 1)

@Entity(tableName = "catalog_items", foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("categoryId"), Index(value = ["kind", "favorite"])])
data class CatalogItemEntity(@PrimaryKey val id: String = newId(), val kind: String = "product", val name: String, val searchKey: String, val categoryId: String? = null, val favorite: Boolean = false, val unit: String = "GRAM", val portionName: String? = null, @Embedded(prefix = "input_") val input: MicroNutrients? = null, val yieldMicros: Long? = null, @Embedded(prefix = "snapshot_") val snapshot: SnapshotColumns, val revision: Long = 1, val sourceFdcId: Long? = null, val sourceDescription: String? = null, val sourceRelease: String? = null, val sourceVerifiedRevision: Long? = null)

@Entity(tableName = "recipe_ingredients", foreignKeys = [ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["sourceItemId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["recipeId", "ordinal"], unique = true), Index("sourceItemId")])
data class RecipeIngredientEntity(@PrimaryKey val id: String = newId(), val recipeId: String, val ordinal: Long, val quantityMicros: Long, @Embedded(prefix = "snapshot_") val snapshot: SnapshotColumns, val sourceItemId: String? = null)

@Entity(tableName = "catalog_recents", foreignKeys = [ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["itemId"], unique = true)])
data class RecentEntity(@PrimaryKey val id: String = newId(), val itemId: String, val lastUsedMillis: Long)

@Entity(tableName = "food_entries", foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["sourceItemId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["localDate", "mealId", "ordinal", "id"]), Index("mealId"), Index("sourceItemId")])
data class FoodEntryEntity(@PrimaryKey val id: String = newId(), val localDate: String, val mealId: String, val ordinal: Long, val kind: String = "portion", @Embedded(prefix = "snapshot_") val snapshot: SnapshotColumns? = null, val quantityMicros: Long? = null, val quickName: String? = null, @Embedded(prefix = "quick_") val quick: MicroNutrients? = null, val sourceItemId: String? = null, val revision: Long = 1, val createdByOperationId: String, val quickNameKey: String? = quickName?.let(NameKeys::normalize)) {
    fun nutrients(): Nutrients = if (kind == "quick") requireNotNull(quick).toDomain() else requireNotNull(snapshot).toDomain().portion(requireNotNull(quantityMicros))
    fun displayName(): String = if (kind == "quick") quickName?.takeIf { it.isNotBlank() } ?: "Быстрая запись" else requireNotNull(snapshot).name
}

@Entity(tableName = "food_day_confirmations", indices = [Index(value = ["localDate"], unique = true)])
data class DayConfirmationEntity(@PrimaryKey val id: String = newId(), val localDate: String, val revision: Long = 1)

@Entity(tableName = "goals", indices = [Index(value = ["startDate"], unique = true)])
data class GoalEntity(@PrimaryKey val id: String = newId(), val startDate: String, @Embedded val nutrients: MicroNutrients, val revision: Long = 1)

@Entity(tableName = "goal_timeline_state", indices = [Index(value = ["slot"], unique = true)])
data class GoalTimelineEntity(@PrimaryKey val id: String = newId(), val slot: Int = 1, val revision: Long = 1)

@Entity(tableName = "weight_records", indices = [Index(value = ["localDate"], unique = true)])
data class WeightEntity(@PrimaryKey val id: String = newId(), val localDate: String, val centiKg: Int, val revision: Long = 1)

@Entity(tableName = "sleep_records", indices = [Index(value = ["localDate"], unique = true)])
data class SleepEntity(@PrimaryKey val id: String = newId(), val localDate: String, val minutes: Int, val revision: Long = 1)

@Entity(tableName = "day_moments", indices = [Index(value = ["localDate"], unique = true)])
data class MomentEntity(@PrimaryKey val id: String = newId(), val localDate: String, val mood: String? = null, val note: String = "", val revision: Long = 1, val mediaRevision: Long = 1)

@Entity(tableName = "meal_templates")
data class TemplateEntity(@PrimaryKey val id: String = newId(), val name: String, val revision: Long = 1)

@Entity(tableName = "template_lines", foreignKeys = [ForeignKey(entity = TemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["sourceItemId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["templateId", "ordinal"], unique = true), Index("sourceItemId")])
data class TemplateLineEntity(@PrimaryKey val id: String = newId(), val templateId: String, val ordinal: Long, @Embedded(prefix = "snapshot_") val snapshot: SnapshotColumns, val quantityMicros: Long, val sourceItemId: String? = null)

@Entity(tableName = "meal_plans", foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["planDate", "mealId"]), Index("mealId")])
data class PlanEntity(@PrimaryKey val id: String = newId(), val planDate: String, val mealId: String, val name: String, val revision: Long = 1)

@Entity(tableName = "plan_lines", foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["sourceItemId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["planId", "ordinal"], unique = true), Index("sourceItemId")])
data class PlanLineEntity(@PrimaryKey val id: String = newId(), val planId: String, val ordinal: Long, @Embedded(prefix = "snapshot_") val snapshot: SnapshotColumns, val quantityMicros: Long, val sourceItemId: String? = null)

@Entity(tableName = "plan_transfers", foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["operationId"], unique = true), Index("sourcePlanId"), Index("mealId")])
data class TransferEntity(@PrimaryKey val id: String = newId(), val operationId: String, val sourcePlanId: String, val sourcePlanName: String, val localDate: String, val mealId: String, val committedAtMillis: Long)

@Entity(tableName = "quick_portion_groups", foreignKeys = [ForeignKey(entity = CatalogItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["itemId", "basisMicros", "unit", "unitLabel"], unique = true)])
data class QuickPortionGroupEntity(@PrimaryKey val id: String = newId(), val itemId: String, val basisMicros: Long, val unit: String, val unitLabel: String, val revision: Long = 1)

@Entity(tableName = "quick_portion_values", foreignKeys = [ForeignKey(entity = QuickPortionGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["groupId", "ordinal"], unique = true), Index(value = ["groupId", "quantityMicros"], unique = true)])
data class QuickPortionEntity(@PrimaryKey val id: String = newId(), val groupId: String, val ordinal: Long, val quantityMicros: Long)

@Entity(tableName = "operation_receipts")
data class ReceiptEntity(@PrimaryKey val operationId: String, val kind: String, val payloadFingerprint: String, val originDraftId: String? = null, val confirmedDraftRevision: Long? = null, val committedAtMillis: Long)

@Entity(tableName = "operation_results", foreignKeys = [ForeignKey(entity = ReceiptEntity::class, parentColumns = ["operationId"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("operationId")])
data class OperationResultEntity(@PrimaryKey val id: String = newId(), val operationId: String, val entityKind: String, val entityId: String)

@Entity(tableName = "drafts", indices = [Index(value = ["kind", "targetEntityId"])])
@Serializable
data class DraftEntity(@PrimaryKey val id: String = newId(), val kind: String, val draftFormatVersion: Int = 1, val draftRevision: Long = 1, val targetEntityId: String? = null, val baseRevision: Long? = null, val selectedLocalDate: String? = null, val mealId: String? = null, val rawPayload: String, val operationId: String? = null, val payloadFingerprint: String? = null, val status: String = "OPEN", val updatedAtMillis: Long, val mediaRevision: Long = 1)

/** Bounded navigation metadata; raw editor payload is fetched only on explicit resume. */
data class DraftHeader(val id: String, val kind: String, val draftFormatVersion: Int, val draftRevision: Long, val targetEntityId: String?, val baseRevision: Long?, val selectedLocalDate: String?, val mealId: String?, val operationId: String?, val status: String, val updatedAtMillis: Long, val mediaRevision: Long)

@Entity(tableName = "reminder_specs", indices = [Index(value = ["kind"], unique = true)])
data class ReminderEntity(@PrimaryKey val id: String = newId(), val kind: String, val enabled: Boolean = false, val minuteOfDay: Int, val weekDaysMask: Int = 127, val revision: Long = 1, val generation: Long = 1)

@Entity(tableName = "reminder_schedule_outbox", foreignKeys = [ForeignKey(entity = ReminderEntity::class, parentColumns = ["id"], childColumns = ["reminderSpecId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["reminderSpecId", "generation"], unique = true)])
data class ReminderOutboxEntity(@PrimaryKey val id: String = newId(), val reminderSpecId: String, val generation: Long, val action: String, val acknowledged: Boolean = false)

@Entity(tableName = "reminder_occurrences", foreignKeys = [ForeignKey(entity = ReminderEntity::class, parentColumns = ["id"], childColumns = ["reminderSpecId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["reminderSpecId", "localDate"], unique = true)])
data class ReminderOccurrenceEntity(@PrimaryKey val id: String = newId(), val reminderSpecId: String, val localDate: String, val generation: Long, val state: String = "CLAIMED", val errorCode: String? = null, val claimedAtMillis: Long)

@Entity(tableName = "media_assets", indices = [Index(value = ["objectKey"], unique = true)])
data class MediaAssetEntity(@PrimaryKey val id: String = newId(), val objectKey: String, val mimeType: String = "image/jpeg", val width: Int = 0, val height: Int = 0, val byteCount: Long = 0, val sha256: String? = null, val state: String = "PREPARING", val generation: Long = 1, val createdAtMillis: Long, val mediaFormatVersion: Int = 1, val integrity: String = "UNCHECKED", val processingProfile: String = "jpeg-srgb")

@Entity(tableName = "photos", foreignKeys = [ForeignKey(entity = MomentEntity::class, parentColumns = ["id"], childColumns = ["momentId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["momentId", "ordinal"], unique = true), Index(value = ["assetId"], unique = true), Index("mealId")])
data class PhotoEntity(@PrimaryKey val id: String = newId(), val momentId: String, val assetId: String, val caption: String = "", val mealId: String? = null, val ordinal: Long, val revision: Long = 1, val captionKey: String = NameKeys.normalize(caption))

@Entity(tableName = "draft_media", foreignKeys = [ForeignKey(entity = DraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["draftId", "assetId", "role"], unique = true), Index("assetId")])
data class DraftMediaEntity(@PrimaryKey val id: String = newId(), val draftId: String, val assetId: String, val role: String, val ordinal: Long)

@Entity(tableName = "media_import_batches")
data class MediaImportBatchEntity(@PrimaryKey val id: String = newId(), val draftId: String, val expectedDraftRevision: Long, val expectedCount: Int, val status: String = "PREPARING", val generation: Long = 1, val createdAtMillis: Long, val selectedLocalDate: String, val expectedMediaRevision: Long, val processSessionId: String, val reservedBytes: Long = 33_554_432, val failureReason: String? = null)

@Entity(tableName = "media_import_items", foreignKeys = [ForeignKey(entity = MediaImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["batchId", "ordinal"], unique = true), Index("assetId")])
data class MediaImportItemEntity(@PrimaryKey val id: String = newId(), val batchId: String, val assetId: String, val ordinal: Long)

@Entity(tableName = "media_read_leases", foreignKeys = [ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)], indices = [Index("assetId")])
data class MediaReadLeaseEntity(@PrimaryKey val id: String = newId(), val assetId: String, val processSessionId: String, val acquiredAtMillis: Long)

@Entity(tableName = "media_cleanup", foreignKeys = [ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["assetId"], unique = true)])
data class MediaCleanupEntity(@PrimaryKey val id: String = newId(), val assetId: String, val generation: Long, val state: String = "PENDING")

data class DietState(
    val ready: Boolean = false, val error: String? = null,
    val profile: ProfileEntity? = null, val onboarding: OnboardingEntity? = null,
    val meals: List<MealEntity> = emptyList(), val categories: List<CategoryEntity> = emptyList(),
    val catalog: List<CatalogItemEntity> = emptyList(), val ingredients: List<RecipeIngredientEntity> = emptyList(),
    val recents: List<RecentEntity> = emptyList(), val entries: List<FoodEntryEntity> = emptyList(),
    val confirmations: List<DayConfirmationEntity> = emptyList(), val goals: List<GoalEntity> = emptyList(),
    val goalTimelineRevision: Long = 1, val weights: List<WeightEntity> = emptyList(),
    val sleeps: List<SleepEntity> = emptyList(), val moments: List<MomentEntity> = emptyList(),
    val photos: List<PhotoEntity> = emptyList(), val assets: List<MediaAssetEntity> = emptyList(),
    val templates: List<TemplateEntity> = emptyList(), val templateLines: List<TemplateLineEntity> = emptyList(),
    val plans: List<PlanEntity> = emptyList(), val planLines: List<PlanLineEntity> = emptyList(),
    val transfers: List<TransferEntity> = emptyList(), val quickPortionGroups: List<QuickPortionGroupEntity> = emptyList(),
    val quickPortions: List<QuickPortionEntity> = emptyList(), val reminders: List<ReminderEntity> = emptyList(),
    val drafts: List<DraftHeader> = emptyList(),
    val draftMedia: List<DraftMediaEntity> = emptyList(),
    val visibleDate: String? = null, val completedDayCount: Long = 0,
    val dataRevision: Long = 0,
)
data class FrequentFood(val name: String, val count: Long)
data class ReportMomentStat(val localDate: String, val mood: String?, val hasNote: Boolean, val photoCount: Long)
