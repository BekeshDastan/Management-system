package app.diet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import app.diet.domain.Numbers

@Serializable
data class PreferenceDraft(val id: String, val group: String, val baseRevision: Long, val draftRevision: Long = 1, val rawFields: Map<String, String>, val status: String = "OPEN", val formatVersion: Int = 1)
data class UnsupportedPreferenceDraft(val group: String, val rawFingerprint: String)

data class VisualPreferences(
    val ready: Boolean = false, val error: String? = null,
    val theme: String = "dark", val largeText: Boolean = false,
    val accent: String = "fern", val cover: String = "5",
    val homeOrder: List<String> = listOf("hero", "nutrition", "meals", "journal", "prompt", "measurements", "level"),
    val hiddenHome: Set<String> = emptySet(), val gameNames: Boolean = true, val gameLevels: Boolean = true,
    val report: Map<String, String> = emptyMap(), val viewContext: Map<String, String> = emptyMap(),
    val desiredWeightCentiKg: Long? = null,
    val revisions: Map<String, Long> = emptyMap(), val drafts: List<PreferenceDraft> = emptyList(),
    val unsupportedDrafts: List<UnsupportedPreferenceDraft> = emptyList(),
) {
    val unsupportedDraftGroups: Set<String> get() = unsupportedDrafts.map { it.group }.toSet()
}

/** Stores preferences and the optional personal weight intention, never measured weight facts. */
class VisualPreferencesRepository internal constructor(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(preferenceStore(context, scope), scope)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val mutableState = MutableStateFlow(VisualPreferences())
    val state: StateFlow<VisualPreferences> = mutableState.asStateFlow()
    private val groups = setOf("theme", "font", "appearance", "home", "gameNames", "gameLevels", "report", "view", "weightTarget")
    private val observationRequests = MutableStateFlow(0L)

    init {
        scope.launch(Dispatchers.IO) {
            observationRequests.collectLatest {
                try { store.data.collect { preferences ->
                    try { mutableState.value = decode(preferences) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { observationFailed(blocked = true) }
                } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { observationFailed() }
            }
        }
    }
    private fun observationFailed(blocked: Boolean = false) { mutableState.update { it.copy(ready = if (blocked) false else it.ready, error = "Не удалось прочитать настройки. Файл сохранён; повторите чтение.") } }
    fun retryObservation() {
        mutableState.update { it.copy(error = null) }
        observationRequests.update { Math.addExact(it, 1L) }
    }
    private fun revisionKey(group: String) = longPreferencesKey("revision.$group")
    private fun payloadKey(group: String) = stringPreferencesKey("value.$group")
    private fun tokenKey(group: String) = stringPreferencesKey("token.$group")
    private fun fingerprintKey(group: String) = stringPreferencesKey("fingerprint.$group")
    private fun draftKey(group: String) = stringPreferencesKey("draft.$group")
    private fun rawFingerprint(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun parseKnownDraft(raw: String, group: String): PreferenceDraft {
        try {
            require(raw.toByteArray(Charsets.UTF_8).size <= 8192)
            val draft = json.decodeFromString<PreferenceDraft>(raw)
            require(draft.formatVersion == 1 && draft.group == group && draft.draftRevision >= 1 && draft.baseRevision >= 1 && draft.status in setOf("OPEN", "COMMITTED", "DISCARDED"))
            return draft
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw DataFailure.Invalid("Этот черновик настроек пока нельзя открыть. Его данные сохранены.") }
    }
    private fun knownDraft(p: Preferences, group: String): PreferenceDraft? = p[draftKey(group)]?.let { parseKnownDraft(it, group) }
    private fun encodedFields(fields: Map<String, String>): String = json.encodeToString<Map<String, String>>(fields.toSortedMap())
    private fun fingerprint(group: String, fields: Map<String, String>, baseRevision: Long): String {
        val encoded = encodedFields(fields)
        val confirmed = json.encodeToString(listOf(group, baseRevision.toString(), encoded))
        return MessageDigest.getInstance("SHA-256").digest(confirmed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    suspend fun freshPreferenceDraft(group: String): PreferenceDraft? {
        require(group in groups)
        val current = store.data.first(); supported(current)
        return knownDraft(current, group)
    }
    /** Explicit discard only; an old dialog cannot remove a newer incompatible draft. */
    suspend fun discardUnsupportedDraft(group: String, expectedRawFingerprint: String) {
        require(group in groups)
        store.edit { current ->
            supported(current)
            val raw = current[draftKey(group)] ?: throw DataFailure.Missing()
            if (rawFingerprint(raw) != expectedRawFingerprint) throw DataFailure.Conflict()
            require(runCatching { parseKnownDraft(raw, group) }.isFailure) { "Черновик уже доступен; откройте его заново" }
            current.remove(draftKey(group))
        }
    }
    /** Returning the same Preferences is a read-only barrier in DataStore's serial update queue. */
    suspend fun recoverPreference(group: String, token: String, fields: Map<String, String>, baseRevision: Long): Boolean {
        require(group in groups); require(java.util.UUID.fromString(token).toString() == token)
        val expected = fingerprint(group, fields, baseRevision)
        var found = false
        store.updateData { current ->
            if (current[tokenKey(group)] == token) {
                if (current[fingerprintKey(group)] != expected) throw DataFailure.Conflict()
                found = true
            } else {
                supported(current)
                // A newer token may have replaced our old receipt; absence is conclusive only
                // while this group's original revision is unchanged.
                if ((current[revisionKey(group)] ?: 1) != baseRevision) throw DataFailure.Conflict()
            }
            current
        }
        return found
    }
    private fun supported(p: Preferences) { require((p[longPreferencesKey("formatVersion")] ?: 1) == 1L) { "Неизвестная версия настроек; файл сохранён" } }
    private fun fields(p: Preferences, group: String): Map<String, String> = p[payloadKey(group)]?.let { json.decodeFromString(it) } ?: emptyMap()
    private fun decode(p: Preferences): VisualPreferences {
        supported(p)
        groups.forEach { group -> if (p[payloadKey(group)] != null) validate(group, fields(p, group)) }
        val appearance = fields(p, "appearance"); val home = fields(p, "home")
        val unsupported = mutableListOf<UnsupportedPreferenceDraft>()
        val drafts = groups.mapNotNull { group ->
            val raw = p[draftKey(group)] ?: return@mapNotNull null
            try { parseKnownDraft(raw, group).takeIf { it.status == "OPEN" } }
            catch (_: DataFailure.Invalid) { unsupported += UnsupportedPreferenceDraft(group, rawFingerprint(raw)); null }
        }
        return VisualPreferences(ready = true,
            theme = fields(p, "theme")["theme"] ?: "dark", largeText = fields(p, "font")["large"]?.toBooleanStrict() ?: false,
            accent = appearance["accent"] ?: "fern", cover = appearance["cover"] ?: "5",
            homeOrder = home["order"]?.split(',') ?: VisualPreferences().homeOrder,
            hiddenHome = home["hidden"]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            gameNames = fields(p, "gameNames")["enabled"]?.toBooleanStrict() ?: true,
            gameLevels = fields(p, "gameLevels")["enabled"]?.toBooleanStrict() ?: true,
            report = fields(p, "report"), viewContext = fields(p, "view"),
            desiredWeightCentiKg = Numbers.parseOptionalWeightCentiKg(fields(p, "weightTarget")["kg"].orEmpty()),
            revisions = groups.associateWith { p[revisionKey(it)] ?: 1 },
            drafts = drafts, unsupportedDrafts = unsupported)
    }
    private fun validate(group: String, fields: Map<String, String>) {
        require(group in groups)
        when (group) {
            "theme" -> require(fields.keys == setOf("theme") && fields["theme"] in listOf("system", "light", "dark"))
            "font" -> require(fields.keys == setOf("large") && fields["large"] in listOf("true", "false"))
            "appearance" -> require(fields.keys == setOf("accent", "cover") && fields["accent"] in listOf("fern", "berry", "ocean") && fields["cover"] in listOf("0", "1", "2", "3", "4", "5"))
            "home" -> { val order = requireNotNull(fields["order"]).split(','); val all = VisualPreferences().homeOrder.toSet(); val hidden = fields["hidden"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(); require(fields.keys == setOf("order", "hidden") && order.size == all.size && order.toSet() == all && hidden.all { it in all }) }
            "gameNames", "gameLevels" -> require(fields.keys == setOf("enabled") && fields["enabled"] in listOf("true", "false"))
            "weightTarget" -> { require(fields.keys == setOf("kg")); Numbers.parseOptionalWeightCentiKg(requireNotNull(fields["kg"])) }
            "report", "view" -> { require(fields.size <= 30); require(fields.all { it.key.length <= 80 && it.value.length <= 512 }) }
        }
    }
    suspend fun checkpoint(draft: PreferenceDraft, expectedRevision: Long?): PreferenceDraft {
        require(draft.group in groups && draft.formatVersion == 1 && draft.status == "OPEN")
        var saved: PreferenceDraft? = null
        store.edit { p ->
            supported(p)
            val old = knownDraft(p, draft.group)
            if (old?.id == draft.id) {
                if (old.status != "OPEN") throw DataFailure.ClosedDraft()
                require(old.formatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
                if (old.draftRevision != expectedRevision) throw DataFailure.Conflict()
            } else if (expectedRevision != null || old?.status == "OPEN") throw DataFailure.Conflict()
            val next = draft.copy(draftRevision = if (old?.id == draft.id) Math.addExact(old.draftRevision, 1) else 1)
            val encoded = json.encodeToString(next); require(encoded.toByteArray().size <= 8192) { "Черновик настроек слишком большой" }
            p[draftKey(draft.group)] = encoded; saved = next
        }
        return requireNotNull(saved)
    }
    suspend fun discard(group: String, draftId: String, expectedRevision: Long) {
        require(group in groups)
        store.edit { p ->
            supported(p)
            val old = knownDraft(p, group) ?: throw DataFailure.Missing()
            if (old.id != draftId || old.draftRevision != expectedRevision) throw DataFailure.Conflict()
            if (old.status != "OPEN") throw DataFailure.ClosedDraft()
            p[draftKey(group)] = json.encodeToString(old.copy(status = "DISCARDED", rawFields = emptyMap(), draftRevision = Math.addExact(old.draftRevision, 1)))
        }
    }
    suspend fun apply(group: String, fields: Map<String, String>, baseRevision: Long, token: String, draftId: String? = null, draftRevision: Long? = null) {
        require(group in groups); require(java.util.UUID.fromString(token).toString() == token)
        val encoded = encodedFields(fields)
        val fp = fingerprint(group, fields, baseRevision)
        store.edit { p ->
            if (p[tokenKey(group)] == token) {
                if (p[fingerprintKey(group)] != fp) throw DataFailure.Conflict()
                return@edit
            }
            supported(p)
            if ((p[revisionKey(group)] ?: 1) != baseRevision) throw DataFailure.Conflict()
            validate(group, fields); require(encoded.toByteArray().size <= 8192)
            val draft = knownDraft(p, group)
            require(draft == null || draft.formatVersion == 1) { "Неизвестная версия черновика; данные сохранены" }
            if (draftId != null && (draft?.id != draftId || draft.draftRevision != draftRevision || draft.status != "OPEN" || draft.baseRevision != baseRevision)) throw DataFailure.Conflict()
            p[payloadKey(group)] = encoded; p[revisionKey(group)] = Math.addExact(baseRevision, 1)
            p[tokenKey(group)] = token; p[fingerprintKey(group)] = fp; p[longPreferencesKey("formatVersion")] = 1
            if (draft != null && draft.status == "OPEN") p[draftKey(group)] = json.encodeToString(draft.copy(status = "COMMITTED", rawFields = emptyMap(), draftRevision = Math.addExact(draft.draftRevision, 1)))
        }
    }

    companion object {
        @Volatile private var shared: DataStore<Preferences>? = null
        /** The production factory and isolated integration stores use the same validated CE path. */
        internal fun preferencesFile(context: Context, name: String = "visual.preferences_pb"): File {
            check(!context.isDeviceProtectedStorage)
            require(name.matches(Regex("[a-zA-Z0-9_.-]+\\.preferences_pb")))
            val dir = File(context.noBackupFilesDir, "diet/prefs").canonicalFile
            check(dir.isDirectory || dir.mkdirs()); check(dir.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
            return File(dir, name)
        }
        private fun preferenceStore(context: Context, scope: CoroutineScope): DataStore<Preferences> = shared ?: synchronized(this) {
            shared ?: run {
                val file = preferencesFile(context)
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).also { shared = it }
            }
        }
    }
}
