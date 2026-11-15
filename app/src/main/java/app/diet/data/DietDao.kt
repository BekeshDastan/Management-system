package app.diet.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Internal persistence surface. UI writes only through DietRepository. */
@Dao
internal interface DietDao {
    @Query("SELECT CASE WHEN kind='quick' THEN CASE WHEN quickName IS NULL OR quickName='' THEN 'Быстрая запись' ELSE quickName END ELSE snapshot_name END AS name, COUNT(*) AS count FROM food_entries WHERE localDate BETWEEN :start AND :end GROUP BY name ORDER BY count DESC, name ASC LIMIT :limit") suspend fun frequentFoods(start: String, end: String, limit: Int = 5): List<FrequentFood>
    @Query("SELECT * FROM food_entries WHERE localDate BETWEEN :start AND :end AND (:mealId IS NULL OR mealId = :mealId) AND (instr(snapshot_nameKey, :query) > 0 OR instr(quickNameKey, :query) > 0 OR (:query = '' AND kind = 'quick')) ORDER BY localDate DESC, ordinal, id LIMIT :limit OFFSET :offset") suspend fun historyPage(start: String, end: String, query: String, mealId: String?, offset: Long, limit: Int): List<FoodEntryEntity>
    @Query("SELECT * FROM catalog_recents ORDER BY lastUsedMillis DESC, id LIMIT 12") suspend fun recentPage(): List<RecentEntity>
    @Query("SELECT id,kind,draftFormatVersion,draftRevision,targetEntityId,baseRevision,selectedLocalDate,mealId,operationId,status,updatedAtMillis,mediaRevision FROM drafts WHERE status = 'OPEN' ORDER BY updatedAtMillis DESC,id LIMIT :limit OFFSET :offset") suspend fun draftHeaders(offset: Long, limit: Int): List<DraftHeader>
    @Query("SELECT id,kind,draftFormatVersion,draftRevision,targetEntityId,baseRevision,selectedLocalDate,mealId,operationId,status,updatedAtMillis,mediaRevision FROM drafts WHERE status = 'OPEN' AND kind = :kind AND targetEntityId IS :targetId AND (:date IS NULL OR selectedLocalDate = :date) AND (:mealId IS NULL OR mealId = :mealId) ORDER BY updatedAtMillis DESC,id LIMIT 1") suspend fun findOpenDraft(kind: String, targetId: String?, date: String?, mealId: String?): DraftHeader?
    @Query("SELECT * FROM draft_media WHERE draftId IN (SELECT id FROM drafts WHERE status = 'OPEN' ORDER BY updatedAtMillis DESC,id LIMIT 20)") suspend fun recentDraftMedia(): List<DraftMediaEntity>
    @Query("SELECT * FROM meal_templates ORDER BY name, id LIMIT :limit OFFSET :offset") suspend fun templatesPage(offset: Long, limit: Int): List<TemplateEntity>
    @Query("SELECT * FROM meal_plans WHERE planDate BETWEEN :start AND :end ORDER BY planDate, name, id LIMIT :limit OFFSET :offset") suspend fun plansPage(start: String, end: String, offset: Long, limit: Int): List<PlanEntity>
    @Query("SELECT * FROM plan_transfers WHERE sourcePlanId = :id ORDER BY committedAtMillis DESC, id LIMIT :limit OFFSET :offset") suspend fun transfersPage(id: String, offset: Long, limit: Int): List<TransferEntity>
    @Query("SELECT * FROM food_entries WHERE localDate BETWEEN :start AND :end ORDER BY localDate, ordinal, id LIMIT :limit OFFSET :offset") suspend fun entriesPage(start: String, end: String, offset: Long, limit: Int): List<FoodEntryEntity>
    @Query("SELECT * FROM food_day_confirmations WHERE localDate BETWEEN :start AND :end ORDER BY localDate") suspend fun confirmationsRange(start: String, end: String): List<DayConfirmationEntity>
    @Query("SELECT * FROM weight_records WHERE localDate BETWEEN :start AND :end ORDER BY localDate") suspend fun weightsRange(start: String, end: String): List<WeightEntity>
    @Query("SELECT * FROM sleep_records WHERE localDate BETWEEN :start AND :end ORDER BY localDate") suspend fun sleepsRange(start: String, end: String): List<SleepEntity>
    @Query("SELECT * FROM weight_records WHERE localDate BETWEEN :start AND :end ORDER BY localDate LIMIT :limit OFFSET :offset") suspend fun weightsPage(start: String, end: String, offset: Long, limit: Int): List<WeightEntity>
    @Query("SELECT * FROM sleep_records WHERE localDate BETWEEN :start AND :end ORDER BY localDate LIMIT :limit OFFSET :offset") suspend fun sleepsPage(start: String, end: String, offset: Long, limit: Int): List<SleepEntity>
    @Query("SELECT * FROM goals WHERE startDate BETWEEN :start AND :end OR startDate = (SELECT MAX(startDate) FROM goals WHERE startDate <= :start) ORDER BY startDate") suspend fun goalsForPeriod(start: String, end: String): List<GoalEntity>
    @Query("SELECT * FROM food_day_confirmations WHERE localDate BETWEEN :start AND :end ORDER BY localDate LIMIT :limit OFFSET :offset") suspend fun confirmationsPage(start: String, end: String, offset: Long, limit: Int): List<DayConfirmationEntity>
    @Query("SELECT * FROM goals WHERE startDate BETWEEN :start AND :end OR startDate = (SELECT MAX(startDate) FROM goals WHERE startDate <= :start) ORDER BY startDate LIMIT :limit OFFSET :offset") suspend fun goalsPageForPeriod(start: String, end: String, offset: Long, limit: Int): List<GoalEntity>
    @Query("SELECT COUNT(*) FROM food_day_confirmations") suspend fun completedDayCount(): Long
    @Query("SELECT * FROM photos WHERE momentId IN (SELECT id FROM day_moments WHERE localDate = :date) ORDER BY ordinal") suspend fun photosOn(date: String): List<PhotoEntity>
    @Query("SELECT * FROM media_assets WHERE id IN (SELECT assetId FROM photos WHERE momentId IN (SELECT id FROM day_moments WHERE localDate = :date)) OR id IN (SELECT assetId FROM draft_media WHERE draftId IN (SELECT id FROM drafts WHERE status = 'OPEN' ORDER BY updatedAtMillis DESC,id LIMIT 20))") suspend fun assetsForDateAndDrafts(date: String): List<MediaAssetEntity>
    @Query("SELECT * FROM meal_plans WHERE planDate = :date ORDER BY id") suspend fun plansOn(date: String): List<PlanEntity>
    @Query("SELECT * FROM plan_lines WHERE planId IN (SELECT id FROM meal_plans WHERE planDate = :date) ORDER BY planId, ordinal") suspend fun planLinesOn(date: String): List<PlanLineEntity>
    @Query("SELECT * FROM catalog_items WHERE instr(searchKey, :query) > 0 AND (:categoryId IS NULL OR categoryId = :categoryId) AND (:favoritesOnly = 0 OR favorite = 1) AND (:kind IS NULL OR kind = :kind) AND (:recentOnly = 0 OR EXISTS (SELECT 1 FROM catalog_recents r WHERE r.itemId = catalog_items.id)) ORDER BY CASE WHEN :recentOnly THEN COALESCE((SELECT lastUsedMillis FROM catalog_recents r WHERE r.itemId = catalog_items.id), 0) ELSE 0 END DESC, searchKey, id LIMIT :limit OFFSET :offset") suspend fun catalogPage(query: String, categoryId: String?, offset: Long, limit: Int, favoritesOnly: Boolean = false, kind: String? = null, recentOnly: Boolean = false): List<CatalogItemEntity>
    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :id ORDER BY ordinal") suspend fun ingredientsIn(id: String): List<RecipeIngredientEntity>
    @Query("SELECT * FROM template_lines WHERE templateId = :id ORDER BY ordinal") suspend fun templateLinesIn(id: String): List<TemplateLineEntity>
    @Query("SELECT * FROM plan_lines WHERE planId = :id ORDER BY ordinal") suspend fun planLinesIn(id: String): List<PlanLineEntity>
    @Query("SELECT p.* FROM photos p JOIN day_moments m ON m.id = p.momentId WHERE m.localDate BETWEEN :start AND :end AND instr(p.captionKey, :query) > 0 AND (:mealId IS NULL OR p.mealId = :mealId) ORDER BY m.localDate DESC, p.ordinal, p.id LIMIT :limit OFFSET :offset") suspend fun photosPage(start: String, end: String, query: String, mealId: String?, offset: Long, limit: Int): List<PhotoEntity>
    @Query("SELECT * FROM reminder_occurrences") suspend fun allReminderOccurrence(): List<ReminderOccurrenceEntity>
    @Query("SELECT * FROM reminder_occurrences WHERE id = :id") suspend fun getReminderOccurrence(id: String): ReminderOccurrenceEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM reminder_occurrences WHERE reminderSpecId = :specId AND localDate = :date)") suspend fun reminderClaimed(specId: String, date: String): Boolean
    @Query("SELECT * FROM reminder_schedule_outbox WHERE acknowledged = 0 ORDER BY id") suspend fun pendingReminderOutbox(): List<ReminderOutboxEntity>
    @Upsert suspend fun put(value: ReminderOccurrenceEntity)
    @Query("SELECT * FROM local_profile") suspend fun allProfile(): List<ProfileEntity>
    @Query("SELECT * FROM local_profile WHERE id = :id") suspend fun getProfile(id: String): ProfileEntity?
    @Upsert suspend fun put(value: ProfileEntity)
    @Query("DELETE FROM local_profile WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("SELECT * FROM onboarding_state") suspend fun allOnboarding(): List<OnboardingEntity>
    @Query("SELECT * FROM onboarding_state WHERE id = :id") suspend fun getOnboarding(id: String): OnboardingEntity?
    @Upsert suspend fun put(value: OnboardingEntity)
    @Query("DELETE FROM onboarding_state WHERE id = :id") suspend fun deleteOnboarding(id: String)
    @Query("SELECT * FROM meals") suspend fun allMeal(): List<MealEntity>
    @Query("SELECT * FROM meals WHERE id = :id") suspend fun getMeal(id: String): MealEntity?
    @Upsert suspend fun put(value: MealEntity)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Query("SELECT * FROM catalog_categories") suspend fun allCategory(): List<CategoryEntity>
    @Query("SELECT * FROM catalog_categories WHERE id = :id") suspend fun getCategory(id: String): CategoryEntity?
    @Upsert suspend fun put(value: CategoryEntity)
    @Query("DELETE FROM catalog_categories WHERE id = :id") suspend fun deleteCategory(id: String)
    @Query("SELECT * FROM catalog_items") suspend fun allCatalogItem(): List<CatalogItemEntity>
    @Query("SELECT * FROM catalog_items WHERE id = :id") suspend fun getCatalogItem(id: String): CatalogItemEntity?
    @Upsert suspend fun put(value: CatalogItemEntity)
    @Query("DELETE FROM catalog_items WHERE id = :id") suspend fun deleteCatalogItem(id: String)
    @Query("SELECT * FROM recipe_ingredients") suspend fun allRecipeIngredient(): List<RecipeIngredientEntity>
    @Query("SELECT * FROM recipe_ingredients WHERE id = :id") suspend fun getRecipeIngredient(id: String): RecipeIngredientEntity?
    @Upsert suspend fun put(value: RecipeIngredientEntity)
    @Query("DELETE FROM recipe_ingredients WHERE id = :id") suspend fun deleteRecipeIngredient(id: String)
    @Query("SELECT * FROM catalog_recents") suspend fun allRecent(): List<RecentEntity>
    @Query("SELECT * FROM catalog_recents WHERE id = :id") suspend fun getRecent(id: String): RecentEntity?
    @Upsert suspend fun put(value: RecentEntity)
    @Query("DELETE FROM catalog_recents WHERE id = :id") suspend fun deleteRecent(id: String)
    @Query("SELECT * FROM food_entries") suspend fun allFoodEntry(): List<FoodEntryEntity>
    @Query("SELECT * FROM food_entries WHERE id = :id") suspend fun getFoodEntry(id: String): FoodEntryEntity?
    @Upsert suspend fun put(value: FoodEntryEntity)
    @Query("DELETE FROM food_entries WHERE id = :id") suspend fun deleteFoodEntry(id: String)
    @Query("SELECT * FROM food_day_confirmations") suspend fun allDayConfirmation(): List<DayConfirmationEntity>
    @Query("SELECT * FROM food_day_confirmations WHERE id = :id") suspend fun getDayConfirmation(id: String): DayConfirmationEntity?
    @Upsert suspend fun put(value: DayConfirmationEntity)
    @Query("DELETE FROM food_day_confirmations WHERE id = :id") suspend fun deleteDayConfirmation(id: String)
    @Query("SELECT * FROM goals") suspend fun allGoal(): List<GoalEntity>
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun getGoal(id: String): GoalEntity?
    @Upsert suspend fun put(value: GoalEntity)
    @Query("DELETE FROM goals WHERE id = :id") suspend fun deleteGoal(id: String)
    @Query("SELECT * FROM goal_timeline_state") suspend fun allGoalTimeline(): List<GoalTimelineEntity>
    @Query("SELECT * FROM goal_timeline_state WHERE id = :id") suspend fun getGoalTimeline(id: String): GoalTimelineEntity?
    @Upsert suspend fun put(value: GoalTimelineEntity)
    @Query("DELETE FROM goal_timeline_state WHERE id = :id") suspend fun deleteGoalTimeline(id: String)
    @Query("SELECT * FROM weight_records") suspend fun allWeight(): List<WeightEntity>
    @Query("SELECT * FROM weight_records WHERE id = :id") suspend fun getWeight(id: String): WeightEntity?
    @Upsert suspend fun put(value: WeightEntity)
    @Query("DELETE FROM weight_records WHERE id = :id") suspend fun deleteWeight(id: String)
    @Query("SELECT * FROM sleep_records") suspend fun allSleep(): List<SleepEntity>
    @Query("SELECT * FROM sleep_records WHERE id = :id") suspend fun getSleep(id: String): SleepEntity?
    @Upsert suspend fun put(value: SleepEntity)
    @Query("DELETE FROM sleep_records WHERE id = :id") suspend fun deleteSleep(id: String)
    @Query("SELECT * FROM day_moments") suspend fun allMoment(): List<MomentEntity>
    @Query("SELECT * FROM day_moments WHERE id = :id") suspend fun getMoment(id: String): MomentEntity?
    @Upsert suspend fun put(value: MomentEntity)
    @Query("DELETE FROM day_moments WHERE id = :id") suspend fun deleteMoment(id: String)
    @Query("SELECT * FROM meal_templates") suspend fun allTemplate(): List<TemplateEntity>
    @Query("SELECT * FROM meal_templates WHERE id = :id") suspend fun getTemplate(id: String): TemplateEntity?
    @Upsert suspend fun put(value: TemplateEntity)
    @Query("DELETE FROM meal_templates WHERE id = :id") suspend fun deleteTemplate(id: String)
    @Query("SELECT * FROM template_lines") suspend fun allTemplateLine(): List<TemplateLineEntity>
    @Query("SELECT * FROM template_lines WHERE id = :id") suspend fun getTemplateLine(id: String): TemplateLineEntity?
    @Upsert suspend fun put(value: TemplateLineEntity)
    @Query("DELETE FROM template_lines WHERE id = :id") suspend fun deleteTemplateLine(id: String)
    @Query("SELECT * FROM meal_plans") suspend fun allPlan(): List<PlanEntity>
    @Query("SELECT * FROM meal_plans WHERE id = :id") suspend fun getPlan(id: String): PlanEntity?
    @Upsert suspend fun put(value: PlanEntity)
    @Query("DELETE FROM meal_plans WHERE id = :id") suspend fun deletePlan(id: String)
    @Query("SELECT * FROM plan_lines") suspend fun allPlanLine(): List<PlanLineEntity>
    @Query("SELECT * FROM plan_lines WHERE id = :id") suspend fun getPlanLine(id: String): PlanLineEntity?
    @Upsert suspend fun put(value: PlanLineEntity)
    @Query("DELETE FROM plan_lines WHERE id = :id") suspend fun deletePlanLine(id: String)
    @Query("SELECT * FROM plan_transfers") suspend fun allTransfer(): List<TransferEntity>
    @Query("SELECT * FROM plan_transfers WHERE id = :id") suspend fun getTransfer(id: String): TransferEntity?
    @Upsert suspend fun put(value: TransferEntity)
    @Query("DELETE FROM plan_transfers WHERE id = :id") suspend fun deleteTransfer(id: String)
    @Query("SELECT * FROM quick_portion_groups") suspend fun allQuickPortionGroup(): List<QuickPortionGroupEntity>
    @Query("SELECT * FROM quick_portion_groups WHERE id = :id") suspend fun getQuickPortionGroup(id: String): QuickPortionGroupEntity?
    @Upsert suspend fun put(value: QuickPortionGroupEntity)
    @Query("DELETE FROM quick_portion_groups WHERE id = :id") suspend fun deleteQuickPortionGroup(id: String)
    @Query("SELECT * FROM quick_portion_values") suspend fun allQuickPortion(): List<QuickPortionEntity>
    @Query("SELECT * FROM quick_portion_values WHERE id = :id") suspend fun getQuickPortion(id: String): QuickPortionEntity?
    @Upsert suspend fun put(value: QuickPortionEntity)
    @Query("DELETE FROM quick_portion_values WHERE id = :id") suspend fun deleteQuickPortion(id: String)
    @Query("SELECT * FROM operation_receipts") suspend fun allReceipt(): List<ReceiptEntity>
    @Query("SELECT * FROM operation_receipts WHERE operationId = :id") suspend fun getReceipt(id: String): ReceiptEntity?
    @Upsert suspend fun put(value: ReceiptEntity)
    @Query("SELECT * FROM operation_results") suspend fun allOperationResult(): List<OperationResultEntity>
    @Query("SELECT * FROM operation_results WHERE id = :id") suspend fun getOperationResult(id: String): OperationResultEntity?
    @Upsert suspend fun put(value: OperationResultEntity)
    @Query("SELECT * FROM drafts") suspend fun allDraft(): List<DraftEntity>
    @Query("SELECT * FROM drafts WHERE id = :id") suspend fun getDraft(id: String): DraftEntity?
    @Upsert suspend fun put(value: DraftEntity)
    @Query("DELETE FROM drafts WHERE id = :id") suspend fun deleteDraft(id: String)
    @Query("SELECT * FROM reminder_specs") suspend fun allReminder(): List<ReminderEntity>
    @Query("SELECT * FROM reminder_specs WHERE id = :id") suspend fun getReminder(id: String): ReminderEntity?
    @Upsert suspend fun put(value: ReminderEntity)
    @Query("DELETE FROM reminder_specs WHERE id = :id") suspend fun deleteReminder(id: String)
    @Query("SELECT * FROM reminder_schedule_outbox") suspend fun allReminderOutbox(): List<ReminderOutboxEntity>
    @Query("SELECT * FROM reminder_schedule_outbox WHERE id = :id") suspend fun getReminderOutbox(id: String): ReminderOutboxEntity?
    @Upsert suspend fun put(value: ReminderOutboxEntity)
    @Query("DELETE FROM reminder_schedule_outbox WHERE id = :id") suspend fun deleteReminderOutbox(id: String)
    @Query("SELECT * FROM media_assets") suspend fun allMediaAsset(): List<MediaAssetEntity>
    @Query("SELECT * FROM media_assets WHERE id = :id") suspend fun getMediaAsset(id: String): MediaAssetEntity?
    @Upsert suspend fun put(value: MediaAssetEntity)
    @Query("DELETE FROM media_assets WHERE id = :id") suspend fun deleteMediaAsset(id: String)
    @Query("SELECT * FROM photos") suspend fun allPhoto(): List<PhotoEntity>
    @Query("SELECT * FROM photos WHERE id = :id") suspend fun getPhoto(id: String): PhotoEntity?
    @Upsert suspend fun put(value: PhotoEntity)
    @Query("DELETE FROM photos WHERE id = :id") suspend fun deletePhoto(id: String)
    @Query("SELECT * FROM draft_media") suspend fun allDraftMedia(): List<DraftMediaEntity>
    @Query("SELECT * FROM draft_media WHERE id = :id") suspend fun getDraftMedia(id: String): DraftMediaEntity?
    @Upsert suspend fun put(value: DraftMediaEntity)
    @Query("DELETE FROM draft_media WHERE id = :id") suspend fun deleteDraftMedia(id: String)
    @Query("SELECT * FROM media_import_batches") suspend fun allMediaImportBatch(): List<MediaImportBatchEntity>
    @Query("SELECT * FROM media_import_batches WHERE id = :id") suspend fun getMediaImportBatch(id: String): MediaImportBatchEntity?
    @Upsert suspend fun put(value: MediaImportBatchEntity)
    @Query("DELETE FROM media_import_batches WHERE id = :id") suspend fun deleteMediaImportBatch(id: String)
    @Query("SELECT * FROM media_import_items") suspend fun allMediaImportItem(): List<MediaImportItemEntity>
    @Query("SELECT * FROM media_import_items WHERE id = :id") suspend fun getMediaImportItem(id: String): MediaImportItemEntity?
    @Upsert suspend fun put(value: MediaImportItemEntity)
    @Query("DELETE FROM media_import_items WHERE id = :id") suspend fun deleteMediaImportItem(id: String)
    @Query("SELECT * FROM media_read_leases") suspend fun allMediaReadLease(): List<MediaReadLeaseEntity>
    @Query("SELECT * FROM media_read_leases WHERE id = :id") suspend fun getMediaReadLease(id: String): MediaReadLeaseEntity?
    @Upsert suspend fun put(value: MediaReadLeaseEntity)
    @Query("DELETE FROM media_read_leases WHERE id = :id") suspend fun deleteMediaReadLease(id: String)
    @Query("SELECT * FROM media_cleanup") suspend fun allMediaCleanup(): List<MediaCleanupEntity>
    @Query("SELECT * FROM media_cleanup WHERE id = :id") suspend fun getMediaCleanup(id: String): MediaCleanupEntity?
    @Upsert suspend fun put(value: MediaCleanupEntity)
    @Query("DELETE FROM media_cleanup WHERE id = :id") suspend fun deleteMediaCleanup(id: String)

    @Query("SELECT * FROM food_entries WHERE localDate = :date ORDER BY ordinal, id") suspend fun entriesOn(date: String): List<FoodEntryEntity>
    @Query("SELECT DISTINCT mealId FROM food_entries WHERE localDate = :date ORDER BY mealId") suspend fun mealsWithEntries(date: String): List<String>
    @Query("SELECT * FROM food_entries WHERE localDate = :date AND mealId = :mealId ORDER BY ordinal, id") suspend fun mealEntries(date: String, mealId: String): List<FoodEntryEntity>
    @Query("SELECT * FROM food_entries WHERE localDate = :date AND mealId = :mealId ORDER BY ordinal, id LIMIT 501") suspend fun mealCopyEntries(date: String, mealId: String): List<FoodEntryEntity>
    @Query("SELECT MAX(ordinal) FROM food_entries WHERE localDate = :date AND mealId = :mealId") suspend fun lastMealOrdinal(date: String, mealId: String): Long?
    @Query("SELECT EXISTS(SELECT 1 FROM food_entries WHERE localDate = :date)") suspend fun hasFood(date: String): Boolean
    @Query("SELECT * FROM catalog_recents WHERE itemId = :itemId") suspend fun recentForItem(itemId: String): RecentEntity?
    @Query("SELECT * FROM quick_portion_groups WHERE itemId = :itemId AND unit = :unit AND basisMicros = :basis AND unitLabel = :label") suspend fun quickPortionGroup(itemId: String, unit: String, basis: Long, label: String): QuickPortionGroupEntity?
    @Query("SELECT * FROM weight_records WHERE localDate = :date") suspend fun weightOn(date: String): WeightEntity?
    @Query("SELECT * FROM sleep_records WHERE localDate = :date") suspend fun sleepOn(date: String): SleepEntity?
    @Query("SELECT * FROM day_moments WHERE localDate = :date") suspend fun momentOn(date: String): MomentEntity?
    @Query("SELECT m.localDate, m.mood, trim(m.note, char(9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)) <> '' AS hasNote, (SELECT COUNT(*) FROM photos p WHERE p.momentId = m.id) AS photoCount FROM day_moments m WHERE m.localDate BETWEEN :start AND :end ORDER BY m.localDate LIMIT :limit OFFSET :offset")
    suspend fun momentStatsPage(start: String, end: String, offset: Long, limit: Int): List<ReportMomentStat>
    @Query("SELECT * FROM food_day_confirmations WHERE localDate = :date") suspend fun confirmationOn(date: String): DayConfirmationEntity?
    @Query("DELETE FROM food_day_confirmations WHERE localDate = :date") suspend fun unconfirm(date: String)
    @Query("SELECT * FROM goals WHERE startDate = :date") suspend fun goalOn(date: String): GoalEntity?
    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :id") suspend fun clearIngredients(id: String)
    @Query("DELETE FROM template_lines WHERE templateId = :id") suspend fun clearTemplateLines(id: String)
    @Query("DELETE FROM plan_lines WHERE planId = :id") suspend fun clearPlanLines(id: String)
    @Query("DELETE FROM quick_portion_values WHERE groupId = :id") suspend fun clearQuickPortions(id: String)
    @Query("SELECT * FROM operation_results WHERE operationId = :id") suspend fun results(id: String): List<OperationResultEntity>
    @Query("SELECT * FROM photos WHERE momentId = :id ORDER BY ordinal, id") suspend fun photosIn(id: String): List<PhotoEntity>
    @Query("SELECT * FROM photos WHERE assetId = :id") suspend fun photoForAsset(id: String): PhotoEntity?
    @Query("SELECT * FROM draft_media WHERE draftId = :id ORDER BY ordinal") suspend fun draftMedia(id: String): List<DraftMediaEntity>
    @Query("DELETE FROM draft_media WHERE draftId = :id") suspend fun clearDraftMedia(id: String)
    @Query("SELECT COUNT(*) FROM photos WHERE assetId = :id") suspend fun photoReferences(id: String): Int
    @Query("SELECT COUNT(*) FROM draft_media WHERE assetId = :id") suspend fun draftReferences(id: String): Int
    @Query("SELECT COUNT(*) FROM media_read_leases WHERE assetId = :id") suspend fun readReferences(id: String): Int
    @Query("SELECT COUNT(*) FROM media_import_items i JOIN media_import_batches b ON b.id = i.batchId WHERE i.assetId = :id AND b.status = 'PREPARING'") suspend fun importReferences(id: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM media_import_batches WHERE status = 'PREPARING')") suspend fun importPreparing(): Boolean
    @Query("SELECT * FROM media_import_batches WHERE status = 'PREPARING' AND processSessionId <> :sessionId") suspend fun previousImports(sessionId: String): List<MediaImportBatchEntity>
    @Query("SELECT * FROM media_import_items WHERE batchId = :id ORDER BY ordinal") suspend fun importItems(id: String): List<MediaImportItemEntity>
    @Query("DELETE FROM media_import_items WHERE assetId = :id") suspend fun clearAssetImports(id: String)
    @Query("DELETE FROM media_read_leases WHERE processSessionId <> :sessionId") suspend fun clearPreviousLeases(sessionId: String)
    @Query("SELECT * FROM media_cleanup WHERE assetId = :id") suspend fun cleanupForAsset(id: String): MediaCleanupEntity?
    @Query("SELECT * FROM media_assets WHERE id > :after ORDER BY id LIMIT :limit") suspend fun mediaAssetsPage(after: String, limit: Int): List<MediaAssetEntity>
    @Query("SELECT * FROM media_assets WHERE objectKey = :key") suspend fun assetByObjectKey(key: String): MediaAssetEntity?
    @Query("SELECT COALESCE(SUM(reservedBytes), 0) FROM media_import_batches") suspend fun importReservedBytes(): Long
    @Query("SELECT * FROM media_import_batches WHERE id > :after AND status IN ('ABORTED', 'FAILED') ORDER BY id LIMIT :limit") suspend fun cleanupBatchesPage(after: String, limit: Int): List<MediaImportBatchEntity>
    @Query("SELECT a.* FROM media_assets a WHERE a.id > :after AND NOT EXISTS(SELECT 1 FROM photos p WHERE p.assetId = a.id) AND NOT EXISTS(SELECT 1 FROM draft_media d WHERE d.assetId = a.id) AND NOT EXISTS(SELECT 1 FROM media_read_leases l WHERE l.assetId = a.id) AND NOT EXISTS(SELECT 1 FROM media_import_items i JOIN media_import_batches b ON b.id = i.batchId WHERE i.assetId = a.id AND b.status = 'PREPARING') ORDER BY a.id LIMIT :limit") suspend fun unownedAssetsPage(after: String, limit: Int): List<MediaAssetEntity>
}
