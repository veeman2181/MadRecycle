package com.ecomadison.app.ml

import android.content.Context
import com.ecomadison.app.domain.model.MaterialType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class KeywordEntryDto(val keyword: String, val materialType: String)

@Serializable
private data class KeywordDictionaryDto(val version: Int, val keywords: List<KeywordEntryDto>)

/**
 * REQ (§5.5 Tier 3 acceptance): the keyword dictionary is a versioned local asset, not hardcoded
 * in Kotlin, so it can be updated without a full app release via the future rules sync channel.
 */
@Singleton
class OcrKeywordDictionary @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: List<Pair<String, MaterialType>>? = null

    /** Longest keyword first so a specific phrase (e.g. "OAT MILK") outranks a generic one ("MILK"). */
    suspend fun match(text: String): MaterialType? {
        val upper = text.uppercase()
        return loadEntries().firstOrNull { (keyword, _) -> upper.contains(keyword) }?.second
    }

    private suspend fun loadEntries(): List<Pair<String, MaterialType>> = mutex.withLock {
        entries?.let { return it }
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val dto = json.decodeFromString<KeywordDictionaryDto>(raw)
        val loaded = dto.keywords
            .map { it.keyword.uppercase() to MaterialType.valueOf(it.materialType) }
            .sortedByDescending { it.first.length }
        entries = loaded
        loaded
    }

    private companion object {
        const val ASSET_NAME = "ocr_keywords_v1.json"
    }
}
