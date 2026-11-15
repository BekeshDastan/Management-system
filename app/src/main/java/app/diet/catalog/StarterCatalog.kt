package app.diet.catalog

import android.content.Context
import app.diet.data.CatalogItemEntity
import app.diet.data.CategoryEntity
import app.diet.data.DietRepository
import app.diet.data.MicroNutrients
import app.diet.data.SnapshotColumns
import app.diet.domain.FoodUnit
import app.diet.domain.NameKeys
import app.diet.domain.NutritionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

object StarterCatalog {
    suspend fun install(context: Context, repository: DietRepository): Unit = withContext(Dispatchers.IO) {
        if (repository.catalogSeedV1Applied()) return@withContext
        val bytes = context.assets.open("starter_catalog.json").use { it.readBytes() }
        require(bytes.size < 256 * 1024) { "Некорректный стартовый каталог" }
        val source = JSONObject(bytes.toString(Charsets.UTF_8))
        require(source.getInt("formatVersion") == 1 && source.getInt("baseGrams") == 100)
        require(source.getString("sourceSha256") == "b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0")
        val categoriesJson = source.getJSONArray("categories")
        val categories = (0 until categoriesJson.length()).map { i ->
            val row = categoriesJson.getJSONObject(i)
            CategoryEntity(id = row.getString("id"), code = row.getString("code"), name = row.getString("name"), ordinal = i.toLong())
        }
        val sourceItems = source.getJSONArray("items")
        require(sourceItems.length() == 60)
        val items = (0 until sourceItems.length()).map { i ->
            val row = sourceItems.getJSONObject(i)
            val values = row.getJSONObject("nutrients")
            val input = MicroNutrients(values.getJSONObject("kcal").getLong("micros"), values.getJSONObject("protein").getLong("micros"),
                values.getJSONObject("fat").getLong("micros"), values.getJSONObject("carbs").getLong("micros"))
            input.validate()
            val name = row.getString("name")
            CatalogItemEntity(id = row.getString("id"), name = name, searchKey = NameKeys.normalize(name),
                categoryId = categories.single { it.code == row.getString("category") }.id, input = input,
                snapshot = SnapshotColumns.from(NutritionSnapshot(name, FoodUnit.GRAM, 100_000_000L, input.toDomain())),
                sourceFdcId = row.getLong("fdcId"), sourceDescription = row.getString("expectedEnglish"),
                sourceRelease = "USDA SR Legacy April 2018", sourceVerifiedRevision = 1L)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        repository.seedCatalogOnce(items, categories, digest)
        Unit
    }
}
