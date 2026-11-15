package app.diet.data

/** The file pipeline must fsync/verify owned bytes BEFORE calling prepared()/publishBatch(). */
class MediaMetadataRepository internal constructor(private val repository: DietRepository) {
    suspend fun beginImport(draftId: String, mediaRevision: Long, count: Int, sessionId: String): Pair<MediaImportBatchEntity, List<MediaAssetEntity>> = repository.metadataTransaction { dao ->
        require(count in 1..4)
        val draft = dao.getDraft(draftId) ?: throw DataFailure.Missing()
        require(draft.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
        if (draft.status != "OPEN" || draft.mediaRevision != mediaRevision) throw DataFailure.Conflict()
        val date = requireNotNull(draft.selectedLocalDate)
        require(dao.draftMedia(draftId).size + count <= 4)
        require(!dao.importPreparing()) { "Дождитесь завершения добавления фото" }
        val batch = MediaImportBatchEntity(draftId = draftId, expectedDraftRevision = draft.draftRevision, expectedCount = count, createdAtMillis = System.currentTimeMillis(), selectedLocalDate = date, expectedMediaRevision = mediaRevision, processSessionId = sessionId)
        dao.put(batch)
        val assets = (0 until count).map { i ->
            val id = newId(); val asset = MediaAssetEntity(id = id, objectKey = "$id.jpg", createdAtMillis = System.currentTimeMillis())
            dao.put(asset); dao.put(MediaImportItemEntity(batchId = batch.id, assetId = id, ordinal = i.toLong())); asset
        }
        batch to assets
    }

    suspend fun prepared(batchId: String, generation: Long, assetId: String, width: Int, height: Int, byteCount: Long, sha256: String, processingProfile: String) = repository.metadataTransaction { dao ->
        val batch = dao.getMediaImportBatch(batchId) ?: throw DataFailure.Missing()
        if (batch.status != "PREPARING" || batch.generation != generation) throw DataFailure.Conflict()
        require(dao.importItems(batchId).any { it.assetId == assetId })
        require(width in 1..2048 && height in 1..2048 && byteCount in 1..5_242_880 && sha256.matches(Regex("[0-9a-f]{64}")))
        val asset = dao.getMediaAsset(assetId) ?: throw DataFailure.Missing(); require(asset.state == "PREPARING")
        dao.put(asset.copy(width = width, height = height, byteCount = byteCount, sha256 = sha256, integrity = "VERIFIED", processingProfile = processingProfile))
    }

    /** All-or-nothing refs, merging into the latest text draft rather than overwriting it. */
    suspend fun publishBatch(batchId: String, generation: Long): DraftEntity = repository.metadataTransaction { dao ->
        val batch = dao.getMediaImportBatch(batchId) ?: throw DataFailure.Missing()
        if (batch.status != "PREPARING" || batch.generation != generation) throw DataFailure.Conflict()
        val draft = dao.getDraft(batch.draftId) ?: throw DataFailure.Missing()
        require(draft.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
        if (draft.status != "OPEN" || draft.mediaRevision != batch.expectedMediaRevision || draft.selectedLocalDate != batch.selectedLocalDate) throw DataFailure.Conflict()
        val members = dao.importItems(batchId)
        val prior = dao.draftMedia(draft.id)
        require(members.size == batch.expectedCount && prior.size + members.size <= 4)
        val assets = members.map { dao.getMediaAsset(it.assetId) ?: throw DataFailure.Missing() }
        require(assets.all { it.state == "PREPARING" && it.integrity == "VERIFIED" }) { "Не все фото подготовлены" }
        val start = prior.maxOfOrNull { it.ordinal }?.let { Math.addExact(it, 1) } ?: 0L
        assets.forEachIndexed { index, asset ->
            dao.put(asset.copy(state = "READY"))
            dao.put(DraftMediaEntity(draftId = draft.id, assetId = asset.id, role = "staged", ordinal = Math.addExact(start, index.toLong())))
        }
        dao.put(batch.copy(status = "READY", reservedBytes = 0))
        val next = draft.copy(draftRevision = Math.addExact(draft.draftRevision, 1), mediaRevision = Math.addExact(draft.mediaRevision, 1), updatedAtMillis = System.currentTimeMillis())
        dao.put(next); next
    }

    suspend fun attachExisting(draftId: String, expectedMediaRevision: Long, assetIds: List<String>): DraftEntity = repository.metadataTransaction { dao ->
        val draft = dao.getDraft(draftId) ?: throw DataFailure.Missing()
        require(draft.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
        if (draft.status != "OPEN" || draft.mediaRevision != expectedMediaRevision) throw DataFailure.Conflict()
        require(assetIds.size <= 4 && assetIds.toSet().size == assetIds.size)
        val moment = requireNotNull(draft.selectedLocalDate).let { dao.momentOn(it) }
        val owned = moment?.let { dao.photosIn(it.id).map { photo -> photo.assetId } } ?: emptyList()
        assetIds.forEach { require(it in owned && dao.getMediaAsset(it)?.state == "READY") }
        dao.clearDraftMedia(draft.id)
        assetIds.forEachIndexed { i, id -> dao.put(DraftMediaEntity(draftId = draft.id, assetId = id, role = "existing", ordinal = i.toLong())) }
        val next = draft.copy(draftRevision = Math.addExact(draft.draftRevision, 1), mediaRevision = Math.addExact(draft.mediaRevision, 1), updatedAtMillis = System.currentTimeMillis()); dao.put(next); next
    }

    suspend fun abortImport(batchId: String, reason: String) = repository.metadataTransaction { dao ->
        val batch = dao.getMediaImportBatch(batchId) ?: return@metadataTransaction
        if (batch.status != "PREPARING") return@metadataTransaction
        dao.put(batch.copy(status = "ABORTED", generation = Math.addExact(batch.generation, 1), failureReason = reason.take(80)))
        dao.importItems(batchId).forEach { member ->
            val asset = dao.getMediaAsset(member.assetId) ?: return@forEach
            dao.put(asset.copy(state = "ABORTED"))
            enqueue(dao, asset)
        }
    }

    /** Call only at startup of the sole application process, before importing/decoding. */
    suspend fun recoverPreviousProcess(currentSessionId: String) = repository.metadataTransaction { dao ->
        dao.clearPreviousLeases(currentSessionId)
        dao.previousImports(currentSessionId).forEach { batch ->
            dao.put(batch.copy(status = "ABORTED", generation = Math.addExact(batch.generation, 1), failureReason = "PROCESS_INTERRUPTED"))
            dao.importItems(batch.id).forEach { member -> dao.getMediaAsset(member.assetId)?.let { asset -> dao.put(asset.copy(state = "ABORTED")); enqueue(dao, asset) } }
        }
    }
    suspend fun acquireRead(assetId: String, sessionId: String): Pair<MediaAssetEntity, MediaReadLeaseEntity> = repository.metadataTransaction { dao ->
        val asset = dao.getMediaAsset(assetId) ?: throw DataFailure.Missing()
        require(asset.state == "READY" && (dao.photoReferences(assetId) > 0 || dao.draftReferences(assetId) > 0)) { "Фото недоступно для чтения" }
        val lease = MediaReadLeaseEntity(assetId = assetId, processSessionId = sessionId, acquiredAtMillis = System.currentTimeMillis()); dao.put(lease); asset to lease
    }
    suspend fun releaseRead(leaseId: String) = repository.metadataTransaction { it.deleteMediaReadLease(leaseId) }
    suspend fun markIntegrity(assetId: String, integrity: String) = repository.metadataTransaction { dao ->
        require(integrity in listOf("VERIFIED", "MISSING", "CORRUPT")); val asset = dao.getMediaAsset(assetId) ?: throw DataFailure.Missing(); dao.put(asset.copy(integrity = integrity))
    }
    suspend fun queueUnowned() = repository.metadataTransaction { dao ->
        var after = ""
        while (true) {
            val page = dao.unownedAssetsPage(after, 100); if (page.isEmpty()) break
            page.forEach { enqueue(dao, it) }; after = page.last().id
        }
    }
    suspend fun claimCleanup(assetId: String): MediaAssetEntity? = repository.metadataTransaction { dao ->
        val asset = dao.getMediaAsset(assetId) ?: return@metadataTransaction null
        if (!unowned(dao, assetId)) return@metadataTransaction null
        val next = asset.copy(state = "DELETING", generation = if (asset.state == "DELETING") asset.generation else Math.addExact(asset.generation, 1)); dao.put(next); enqueue(dao, next); next
    }
    /** Only after unlink AND directory fsync succeeded. A failure leaves the tombstone. */
    suspend fun finishCleanup(assetId: String, generation: Long) = repository.metadataTransaction { dao ->
        val asset = dao.getMediaAsset(assetId) ?: return@metadataTransaction
        require(asset.state == "DELETING" && asset.generation == generation && unowned(dao, assetId))
        dao.clearAssetImports(assetId)
        dao.deleteMediaAsset(assetId)
    }
    suspend fun assets(): List<MediaAssetEntity> = repository.metadataTransaction { it.allMediaAsset() }
    suspend fun batches(): List<MediaImportBatchEntity> = repository.metadataTransaction { it.allMediaImportBatch() }
    suspend fun assetsPage(after: String = ""): List<MediaAssetEntity> = repository.database.dao().mediaAssetsPage(after, 100)
    suspend fun unownedPage(after: String = ""): List<MediaAssetEntity> = repository.database.dao().unownedAssetsPage(after, 100)
    suspend fun cleanupBatchesPage(after: String = ""): List<MediaImportBatchEntity> = repository.database.dao().cleanupBatchesPage(after, 100)
    suspend fun reservedBytes(): Long = repository.database.dao().importReservedBytes()
    suspend fun assetByObjectKey(key: String): MediaAssetEntity? = repository.database.dao().assetByObjectKey(key)
    suspend fun releaseBatchReserve(batchId: String) = repository.metadataTransaction { dao ->
        val batch = dao.getMediaImportBatch(batchId) ?: return@metadataTransaction
        // A cancelled caller may have lost the reply after publish committed successfully.
        if (batch.status == "READY") { check(batch.reservedBytes == 0L); return@metadataTransaction }
        require(batch.status in listOf("ABORTED", "FAILED", "CLEANED"))
        require(dao.importItems(batchId).isEmpty())
        dao.put(batch.copy(status = "CLEANED", reservedBytes = 0))
    }
    suspend fun draftAssets(draftId: String): List<MediaAssetEntity> = repository.metadataTransaction { dao -> dao.draftMedia(draftId).map { dao.getMediaAsset(it.assetId) ?: throw DataFailure.Missing() } }
    /** Read the durable batch checkpoint directly; Flow delivery is intentionally asynchronous. */
    suspend fun draftPhotoInputs(draftId: String): List<PhotoInput> = repository.metadataTransaction { dao ->
        val draft = dao.getDraft(draftId) ?: throw DataFailure.Missing()
        if (draft.status != "OPEN") throw DataFailure.ClosedDraft()
        dao.draftMedia(draftId).map { reference ->
            if (reference.role == "existing") {
                val photo = dao.photoForAsset(reference.assetId) ?: throw DataFailure.Missing()
                PhotoInput(photo.id, photo.assetId, photo.caption, photo.mealId)
            } else {
                require(reference.role == "staged")
                PhotoInput(reference.id, reference.assetId)
            }
        }
    }
    suspend fun removeDraftAsset(draftId: String, expectedMediaRevision: Long, assetId: String): DraftEntity = repository.metadataTransaction { dao ->
        val draft = dao.getDraft(draftId) ?: throw DataFailure.Missing()
        require(draft.draftFormatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
        if (draft.status != "OPEN" || draft.mediaRevision != expectedMediaRevision) throw DataFailure.Conflict()
        val reference = dao.draftMedia(draftId).find { it.assetId == assetId } ?: throw DataFailure.Missing()
        dao.deleteDraftMedia(reference.id)
        dao.getMediaAsset(assetId)?.let { enqueue(dao, it) }
        val next = draft.copy(draftRevision = Math.addExact(draft.draftRevision, 1), mediaRevision = Math.addExact(draft.mediaRevision, 1), updatedAtMillis = System.currentTimeMillis()); dao.put(next); next
    }
    private suspend fun unowned(dao: DietDao, id: String) = dao.photoReferences(id) == 0 && dao.draftReferences(id) == 0 && dao.readReferences(id) == 0 && dao.importReferences(id) == 0
    private suspend fun enqueue(dao: DietDao, asset: MediaAssetEntity) {
        val old = dao.cleanupForAsset(asset.id)
        dao.put(MediaCleanupEntity(old?.id ?: newId(), asset.id, asset.generation))
    }
}
