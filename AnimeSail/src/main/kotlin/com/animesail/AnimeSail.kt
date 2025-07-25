package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSail : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
                url,
                headers =
                        mapOf(
                                "Accept" to
                                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                        ),
                cookies = mapOf("_as_ipin_ct" to "ID"),
                referer = ref
        )
    }

    override val mainPage =
            mainPageOf(
                    "$mainUrl/page/" to "Episode Terbaru",
                    "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
                    "$mainUrl/genres/donghua/page/" to "Donghua",
                    "$mainUrl/genres/action/page/" to "Action",
                    "$mainUrl/genres/adult-cast/page/" to "Adult Cast",
                    "$mainUrl/genres/adventure/page/" to "Adventure",
                    "$mainUrl/genres/award-winning/page/" to "Award Winning",
                    "$mainUrl/genres/comedy/page/" to "Comedy",
                    "$mainUrl/genres/demons/page/" to "Demons",
                    "$mainUrl/genres/donghua/page/" to "Donghua",
                    "$mainUrl/genres/drama/page/" to "Drama",
                    "$mainUrl/genres/ecchi/page/" to "Ecchi",
                    "$mainUrl/genres/fantasy/page/" to "Fantasy",
                    "$mainUrl/genres/game/page/" to "Game",
                    "$mainUrl/genres/harem/page/" to "Harem",
                    "$mainUrl/genres/historical/page/" to "Historical",
                    "$mainUrl/genres/horror/page/" to "Horror",
                    "$mainUrl/genres/isekai/page/" to "Isekai",
                    "$mainUrl/genres/kids/page/" to "Kids",
                    "$mainUrl/genres/magic/page/" to "Magic",
                    "$mainUrl/genres/martial-arts/page/" to "Martial Arts",
                    "$mainUrl/genres/mecha/page/" to "Mecha",
                    "$mainUrl/genres/military/page/" to "Military",
                    "$mainUrl/genres/music/page/" to "Music",
                    "$mainUrl/genres/mystery/page/" to "Mystery",
                    "$mainUrl/genres/mythology/page/" to "Mythology",
                    "$mainUrl/genres/parody/page/" to "Parody",
                    "$mainUrl/genres/psychological/page/" to "Psychological",
                    "$mainUrl/genres/reincarnation/page/" to "Reincarnation",
                    "$mainUrl/genres/school/page/" to "School",
                    "$mainUrl/genres/sci-fi/page/" to "Sci-Fi",
                    "$mainUrl/genres/seinen/page/" to "Seinen",
                    "$mainUrl/genres/shoujo/page/" to "Shoujo",
                    "$mainUrl/genres/shounen/page/" to "Shounen",
                    "$mainUrl/genres/slice-of-life/page/" to "Slice of Life",
                    "$mainUrl/genres/space/page/" to "Space",
                    "$mainUrl/genres/sports/page/" to "Sports",
                    "$mainUrl/genres/super-power/page/" to "Super Power",
                    "$mainUrl/genres/supernatural/page/" to "Supernatural",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title =
                    when {
                        (title.contains("-episode")) && !(title.contains("-movie")) ->
                                title.substringBefore("-episode")
                        (title.contains("-movie")) -> title.substringBefore("-movie")
                        else -> title
                    }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src") ?: "")
        val epNum =
                this.selectFirst(".tt > h2")?.text()?.let {
                    Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()
                .toString()
                .replace("Subtitle Indonesia", "")
                .trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        // --- Corrected Episode Mapping ---
        val episodes =
            document.select("ul.daftar > li") // Assuming this is your episode list selector
                .mapNotNull { episodeElement -> // Use mapNotNull to safely skip if data is missing
                    val anchor = episodeElement.selectFirst("a") ?: return@mapNotNull null
                    val episodeLink = fixUrl(anchor.attr("href"))
                    val episodeName = anchor.text()

                    val episodeNumber = // Renamed from 'episode' to avoid confusion with property name
                        Regex("Episode\\s?(\\d+)")
                            .find(episodeName)
                            ?.groupValues
                            ?.getOrNull(1) // IMPORTANT: Group 1 for the number
                            ?.toIntOrNull()

                    // --- How to get runtime? This is still a question. ---
                    // val runtimeString = episodeElement.selectFirst(".runtime-class")?.text()
                    // val runtimeInSeconds = parseRuntime(runtimeString) // You'd need a parseRuntime function

                    newEpisode(episodeLink) { // 'episodeLink' is the 'data' argument
                        this.name = episodeName       // Set the 'name' property
                        this.episode = episodeNumber  // Set the 'episode' property (the number)
                        // this.runtime = runtimeInSeconds // Uncomment and implement if you can get runtime
                        // Set other properties if available:
                        // this.season = ...
                        // this.posterUrl = ...
                        // this.description = ...
                        // this.date = ... (parse from page if available)
                    }
                }
                .reversed()
        // --- End Corrected Episode Mapping ---

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = request(data).document

        document.select(".mobius > .mirror > option").apmap {
            safeApiCall {
                val iframe =
                        fixUrl(
                            Jsoup.parse(base64Decode(it.attr("data-em")))
                                    .select("iframe")
                                    .attr("src")
                        )
                val quality = getIndexQuality(it.text())
                when {
                    iframe.startsWith("$mainUrl/utils/player/arch/") ||
                            iframe.startsWith("$mainUrl/utils/player/race/") ->
                            request(iframe, ref = data).document.select("source").attr("src").let {
                                    link ->
                                val source =
                                        when {
                                            iframe.contains("/arch/") -> "Arch"
                                            iframe.contains("/race/") -> "Race"
                                            else -> this.name
                                        }
                                callback.invoke(
                                        ExtractorLink(
                                                source = source,
                                                name = source,
                                                url = link,
                                                referer = mainUrl,
                                                quality = quality
                                        )
                                )
                            }
                    //                    skip for now
                    //                    iframe.startsWith("$mainUrl/utils/player/fichan/") -> ""
                    //                    iframe.startsWith("$mainUrl/utils/player/blogger/") -> ""
                    iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                        val link =
                                "https://rasa-cintaku-semakin-berantai.xyz/v/${
                            iframe.substringAfter("id=").substringBefore("&token")
                        }"
                        loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                    }
                    iframe.startsWith("$mainUrl/utils/player/framezilla/") ||
                            iframe.startsWith("https://uservideo.xyz") -> {
                        request(iframe, ref = data).document.select("iframe").attr("src").let { link
                            ->
                            loadFixedExtractor(
                                    fixUrl(link),
                                    quality,
                                    mainUrl,
                                    subtitleCallback,
                                    callback
                            )
                        }
                    }
                    else -> {
                        loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
            url: String,
            quality: Int?,
            referer: String? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                    ExtractorLink(
                            link.name,
                            link.name,
                            link.url,
                            link.referer,
                            if (link.type == ExtractorLinkType.M3U8) link.quality
                            else quality ?: Qualities.Unknown.value,
                            link.type,
                            link.headers,
                            link.extractorData
                    )
            )
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
    }
}
