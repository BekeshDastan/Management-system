package app.diet.integration

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diet.catalog.StarterCatalog
import app.diet.data.*
import app.diet.domain.FoodUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Bundled public food references and synthetic diary facts, in an isolated real on-device DB. */
@RunWith(AndroidJUnit4::class)
class StarterCatalogIntegrationTest {
    private lateinit var fixture: IntegrationDatabase
    @Before fun setup(): Unit = runBlocking {
        fixture = IntegrationDatabase(ApplicationProvider.getApplicationContext())
        fixture.initialize()
    }
    @After fun cleanup(): Unit = runBlocking { if (::fixture.isInitialized) fixture.close() }

    @Test fun realBundledCatalogHasVerifiedProvenanceAndDoesNotCreateDiaryFacts(): Unit = runBlocking {
        val repo = fixture.repository
        StarterCatalog.install(fixture.context, repo)
        val catalog = repo.queryCatalog(limit = 100)
        assertEquals(60, catalog.size)
        assertTrue(catalog.all { (it.sourceFdcId ?: 0) > 0 && !it.sourceDescription.isNullOrBlank() && it.sourceRelease == "USDA SR Legacy April 2018" && it.sourceVerifiedRevision == it.revision })
        assertTrue(catalog.all { it.unit == "GRAM" && it.snapshot.basisMicros == 100_000_000L && it.input!!.toDomain() == it.snapshot.toDomain().nutrientsPerBasis })
        // Independent agreed SR Legacy reference case; not computed by the importer under test.
        val banana = catalog.single { it.sourceFdcId == 173944L }
        assertEquals("Bananas, raw", banana.sourceDescription)
        assertEquals(MicroNutrients(89_000_000, 1_090_000, 330_000, 22_840_000), banana.input)
        assertTrue(repo.queryHistory("1900-01-01", "9999-12-31").isEmpty())
        assertTrue(repo.state.value.weights.isEmpty()); assertTrue(repo.state.value.sleeps.isEmpty()); assertTrue(repo.state.value.goals.isEmpty())
        assertFalse(repo.state.value.onboarding!!.completed)
        assertNull(repo.state.value.profile!!.initialWeightCentiKg)
        assertEquals(4, repo.state.value.meals.size)
        val initial = catalog.associateBy { it.id }
        StarterCatalog.install(fixture.context, repo)
        assertEquals(initial, repo.queryCatalog(limit = 100).associateBy { it.id })
        assertEquals(1, fixture.database.dao().allReceipt().count { it.kind == "catalog-seed-v1" })
    }

    @Test fun editsDeletionAndFactSnapshotSurviveReopenAndChangedBundledInput(): Unit = runBlocking {
        var repo = fixture.repository
        StarterCatalog.install(fixture.context, repo)
        val original = repo.queryCatalog(limit = 100)
        val banana = original.single { it.sourceFdcId == 173944L }
        val removed = original.first { it.id != banana.id }
        val categories = repo.state.value.categories
        val marker = fixture.database.dao().allReceipt().single { it.kind == "catalog-seed-v1" }
        repo.addFood("2026-11-15", repo.state.value.meals.first().id, LineInput(banana.snapshot, 80_000_000, banana.id), Operation())
        val fact = repo.state.value.entries.single()
        assertEquals("71,2", fact.nutrients().kcal.format(1))
        repo.saveProduct(banana.id, "Синтетическая правка", FoodUnit.GRAM, null, banana.input!!.copy(kcal = 999_000_000), banana.categoryId, banana.revision, Operation())
        val edited = repo.loadCatalogItem(banana.id)!!
        assertEquals(banana.sourceFdcId, edited.sourceFdcId)
        assertEquals(banana.sourceDescription, edited.sourceDescription)
        assertNotEquals(edited.revision, edited.sourceVerifiedRevision)
        repo.toggleFavorite(removed.id, true, removed.revision, Operation())
        val favorited = repo.loadCatalogItem(removed.id)!!
        assertEquals(favorited.revision, favorited.sourceVerifiedRevision)
        repo.deleteCatalogItem(removed.id, favorited.revision, Operation())
        fixture.reopen(); repo = fixture.repository
        // A completed V1 installation must not even read an incompatible replacement asset.
        val unavailableFutureAsset = object : ContextWrapper(fixture.context) {
            override fun getAssets(): AssetManager = throw AssertionError("An installed V1 marker must precede asset access")
        }
        StarterCatalog.install(unavailableFutureAsset, repo)
        val futureItem = original.last().copy(id = newId())
        val replay = repo.seedCatalogOnce(original + futureItem, categories, "f".repeat(64))
        assertTrue(replay.replayed)
        assertEquals(marker, repo.recover(marker.operationId)!!.first)
        assertEquals(edited, repo.loadCatalogItem(banana.id))
        assertNull(repo.loadCatalogItem(removed.id)); assertNull(repo.loadCatalogItem(futureItem.id))
        assertEquals(59, repo.queryCatalog(limit = 100).size)
        assertEquals(fact, repo.loadEntry(fact.id))
        assertEquals("71,2", repo.loadEntry(fact.id)!!.nutrients().kcal.format(1))
        assertEquals(1, fixture.database.dao().allReceipt().count { it.kind == "catalog-seed-v1" })
    }
}
