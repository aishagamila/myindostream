package com.anoboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.ArrayList


class Anoboy : MainAPI() {
    override var mainUrl = "https://ww3.anoboy.app"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val MAIN_IMAGE_URL = "https://ww25.upload.anoboy.life" // Corrected name

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Release",
        "$mainUrl/category/anime-movie/page/" to "Anime Movie",
        "$mainUrl/category/live-action-movie/page/" to "Live Action Movie",
        "$mainUrl/category/anime/page/" to "Anime",
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(
            "$mainUrl/my-ajax?page=$page${request.data}",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json response")
        return newHomePageResponse(request.name, home)
    }

    private fun Anime.toSearchResponse(): SearchResponse? {
        return newAnimeSearchResponse(
            postTitle ?: return null,
            "$mainUrl/anime/$postName", // Assuming mainUrl is a class property
            TvType.TvSeries,
        ) {
            this.posterUrl = "$MAIN_IMAGE_URL/$image" // Use the updated constant name
            addSub(totalEpisode?.toIntOrNull())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/my-ajax?page=1&limit=10&action=load_search_movie&keyword=$query",
            referer = "$mainUrl/search/?keyword=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString()
        val poster = document.selectFirst(".thumbposter > img")?.attr("src")
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst("div.info-content .spe span:last-child")?.ownText()?.lowercase() ?: "tv"

        val year = Regex("\\d, (\\d*)").find(
            document.selectFirst("div.info-content .spe span.split")?.ownText().toString()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(document.selectFirst(".spe > span")!!.ownText())
        val description = document.select("div[itemprop = description] > p").text()
// Inside the .map { ... } block in your load function:

        val episodes = document.select(".eplister > ul > li").mapNotNull { episodeElement -> // Switched to mapNotNull
            val anchor = episodeElement.selectFirst("a") ?: return@mapNotNull null // Get the anchor tag
            val link = anchor.attr("href")
            val episodeNameText = episodeElement.selectFirst(".epl-title")?.text() ?: anchor.text() // Get the full title

            val episodeNumber = Regex("Episode\\s?(\\d+)")
                .find(episodeNameText)
                ?.groupValues
                ?.getOrNull(1) // Group 1 for the actual number
                ?.toIntOrNull()

            // --- Placeholder for runTime extraction ---
            // val runTimeString = episodeElement.selectFirst(".episode-duration-class")?.text() // Adjust selector based on Anoboy's HTML
            // var runTimeInSeconds: Long? = null
            // if (runTimeString != null) {
            //     runTimeInSeconds = parseMyDurationFormat(runTimeString) // You'll need a helper function
            // }
            // --- End placeholder ---

            // Replace the old constructor call (that was on/around line 106) with this:
            newEpisode(link) { // 'link' is the 'data' argument
                this.name = episodeNameText       // Set the 'name' property (full title)
                this.episode = episodeNumber      // Set the 'episode' property (the number)
                // this.runtime = runTimeInSeconds // Set the 'runtime' property if you can get it
                // Add other properties if available and needed:
                // this.season = ...
                // this.posterUrl = ... (if available per episode)
                // this.description = ... (if available per episode)
                // this.date = ... (if upload date is available per episode)
            }
        }.reversed()
        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("div.player-container iframe").attr("src").substringAfter("html#")
            .let { id ->
                app.get("https://gomunimes.com/stream?id=$id")
                    .parsedSafe<Sources>()?.server?.streamsb?.link?.let { link ->
                        loadExtractor(link.replace("vidgomunimesb.xyz", "watchsb.com"), mainUrl, subtitleCallback, callback)
                    }
            }

        return true
    }

    data class Streamsb(
        @JsonProperty("link") val link: String?,
    )

    data class Server(
        @JsonProperty("streamsb") val streamsb: Streamsb?,
    )

    data class Sources(
        @JsonProperty("server") val server: Server?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Anime>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("post_title") val postTitle: String?,
        @JsonProperty("post_name") val postName: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("total_episode") val totalEpisode: String?,
        @JsonProperty("salt") val salt: String?,
    )

}
