package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Arsiv Filmleri"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = false

    data class SearchResult(val response: SearchBody? = null)
    data class SearchBody(val docs: List<SearchDoc>? = null)
    data class SearchDoc(
        val identifier: String? = null,
        val title: JsonElement? = null
    )

    data class MetaResult(
        val files: List<MetaFile>? = null,
        val metadata: MetaData? = null
    )

    data class MetaFile(
        val name: String? = null,
        val format: String? = null
    )

    data class MetaData(
        val title: JsonElement? = null,
        val description: JsonElement? = null,
        val date: JsonElement? = null
    )

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonElement?.asText(): String? =
        when (this) {
            null -> null
            is JsonPrimitive -> contentOrNull
            else -> toString()
        }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanedQuery = query
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .trim()

        if (cleanedQuery.isBlank()) return emptyList()

        val archiveQuery =
            "title:($cleanedQuery) AND collection:feature_films AND mediatype:movies"

        val url = "$mainUrl/advancedsearch.php?q=${encode(archiveQuery)}" +
            "&fl[]=identifier&fl[]=title&rows=20&output=json"

        val result = tryParseJson<SearchResult>(app.get(url).text)

        return result?.response?.docs.orEmpty().mapNotNull { doc ->
            val id = doc.identifier ?: return@mapNotNull null
            val title = doc.title.asText() ?: id

            newMovieSearchResponse(
                title,
                "$mainUrl/details/$id",
                TvType.Movie
            ) {
                posterUrl = "$mainUrl/services/img/$id"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/")
        val meta = tryParseJson<MetaResult>(
            app.get("$mainUrl/metadata/$id").text
        ) ?: return null

        val title = meta.metadata?.title.asText() ?: id
        val description = meta.metadata?.description.asText()
            ?.replace(Regex("<[^>]*>"), " ")
            ?.trim()
        val year = meta.metadata?.date.asText()
            ?.take(4)
            ?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            posterUrl = "$mainUrl/services/img/$id"
            plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val meta = tryParseJson<MetaResult>(
            app.get("$mainUrl/metadata/$data").text
        ) ?: return false

        val file = meta.files.orEmpty().firstOrNull {
            it.name?.endsWith(".mp4", ignoreCase = true) == true
        } ?: return false

        val fileName = file.name ?: return false
        val encodedPath = fileName
            .split("/")
            .joinToString("/") { encode(it) }

        val videoUrl = "$mainUrl/download/$data/$encodedPath"

        callback(
            newExtractorLink(
                name,
                name,
                videoUrl,
                ExtractorLinkType.VIDEO
            ) {
                quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
