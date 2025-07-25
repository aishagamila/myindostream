package com.gomov

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element

open class Gomov : MainAPI() {

    override var mainUrl = "https://gomov.top/"

    private var directUrl: String? = null
    override var name = "Gomov"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage =
            mainPageOf(
                    "best-rating/page/%d/" to "Best Rating",
                    "tv/page/%d/" to "TV Series",
                    "category/asia/page/%d/" to "Asia",
                    "category/korean/page/%d/" to "Korean",
                    "category/india/page/%d/" to "Indian",
                    "category/western/page/%d/" to "Western",
                    "category/western-series/page/%d/" to "Western Series",
                    "category/korean-series/page/%d/" to "Korean Series",
                    "category/chinese-series/page/%d/" to "Chinese Series",
                    "category/india-series/page/%d/" to "India Series",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality =
                this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode =
                    Regex("Episode\\s?([0-9]+)")
                            .find(title)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
                .document
                .select("article.item")
                .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        val title =
                document.selectFirst("h1.entry-title")
                        ?.text()
                        ?.substringBefore("Season")
                        ?.substringBefore("Episode")
                        ?.trim()
                        .toString()
        val poster =
                fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
                        ?.fixImageQuality()
        val tags = document.select("span.gmr-movie-genre:contains(Genre:) > a").map { it.text() }

        val year =
                document.select("span.gmr-movie-genre:contains(Year:) > a")
                        .text()
                        .trim()
                        .toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating =
                document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
                        ?.text()
                        ?.toRatingInt()
        val actors =
                document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map {
                    it.select("a").text()
                }

        val recommendations =
                document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes =
                document.select("div.vid-episodes a, div.gmr-listseries a")
                    .mapNotNull { epsElement -> // Changed to mapNotNull for safer parsing
                        val href = fixUrl(epsElement.attr("href"))
                        val name = epsElement.text()

                        val episodeNumber = // Renamed from 'episode' to avoid confusion
                            name.split(" ")
                                .lastOrNull()
                                ?.filter { it.isDigit() }
                                ?.toIntOrNull()

                        // If episodeNumber is null, we might not want this episode
                        if (episodeNumber == null) return@mapNotNull null

                        val seasonNumber = // Renamed from 'season'
                            name.split(" ")
                                .firstOrNull()
                                ?.filter { it.isDigit() }
                                ?.toIntOrNull()

                        // --- Placeholder for runTime extraction ---
                        // val runTimeString = epsElement.selectFirst(".ep-duration")?.text() // Adjust selector
                        // var runTimeInSeconds: Long? = null
                        // if (runTimeString != null) {
                        //     runTimeInSeconds = parseGomovDuration(runTimeString) // Implement this
                        // }
                        // --- End placeholder ---

                        // Replace the old constructor call (that was on line 136) with this:
                        newEpisode(href) { // 'href' is the 'data' argument
                            this.name = name                 // Set the 'name' property (full title)
                            this.episode = episodeNumber     // Set the 'episode' property (parsed number)
                            this.season = if (name.contains(" ")) seasonNumber else null // Set season
                            // this.runtime = runTimeInSeconds  // Set the 'runtime' property if you can get it
                            // Add other properties if available and needed:
                            // this.posterUrl = ...
                            // this.description = ...
                            // this.date = ...
                        }
                    }
                    // .filter { it.episode != null } // No longer needed if mapNotNull handles it based on episodeNumber
                            .filter { it.episode != null }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").apmap { ele ->
                val iframe =
                        app.get(fixUrl(ele.attr("href")))
                                .document
                                .selectFirst("div.gmr-embed-responsive iframe")
                                .getIframeAttr()
                                ?.let { httpsify(it) }
                                ?: return@apmap

                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").apmap { ele ->
                val server =
                        app.post(
                                        "$directUrl/wp-admin/admin-ajax.php",
                                        data =
                                                mapOf(
                                                        "action" to "muvipro_player_content",
                                                        "tab" to ele.attr("id"),
                                                        "post_id" to "$id"
                                                )
                                )
                                .document
                                .select("iframe")
                                .attr("src")
                                .let { httpsify(it) }

                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
