package com.veena.newpipe

import android.util.Log
import com.indus.veena.contract.AddonEntryPoint
import com.indus.veena.contract.ExtSong
import com.indus.veena.contract.ExtensionHost
import com.indus.veena.contract.MusicAddon
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
@AddonEntryPoint
class Plugin : com.indus.veena.contract.CatalogAddon, com.indus.veena.contract.MediaRequestAddon {
    private lateinit var host: ExtensionHost
    private val TAG = "VEENA_NEWPIPE_PLUGIN"

    override fun getDownloadHeaders(songId: String) = mapOf("Range" to "bytes=0-", "Accept-Encoding" to "identity")

    override fun onLoad(host: ExtensionHost) {
        this.host = host
        run {
            Log.d(TAG, "Initializing NewPipe Extractor environment...")
            NewPipe.init(object : Downloader() {
                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                    val url = request.url()
                    val httpMethod = request.httpMethod()
                    val data = request.dataToSend()
                    return try {
                        val extraHeaders = request.headers()
                            .mapValues { it.value.firstOrNull() ?: "" }

                        val httpResponse = if (httpMethod == "POST") {
                            val bodyStr = data?.let { String(it) } ?: ""
                            host.httpPostFull(
                                url,
                                bodyStr,
                                "application/json",
                                extraHeaders
                            )
                        } else {
                            host.httpGetFull(url, extraHeaders)
                        }
                        // Now we have real status + headers — NewPipe can handle 403s properly
                        if (httpResponse.code != 200) {
                            Log.e(
                                TAG,
                                "NewPipe request got [${httpResponse.code}] for $url"
                            )
                        }

                        Response(
                            httpResponse.code,
                            "", // message not critical for NewPipe
                            httpResponse.headers,
                            httpResponse.body,
                            httpResponse.finalUrl
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "NewPipe downloader failed for $url", t)
                        throw t
                    }
                }
            }, org.schabi.newpipe.extractor.localization.Localization(
                java.util.Locale.getDefault().language.ifBlank { "en" },
                java.util.Locale.getDefault().country.ifBlank { "IN" }),
                org.schabi.newpipe.extractor.localization.ContentCountry(java.util.Locale.getDefault().country.ifBlank { "IN" }))

            Log.d(TAG, "NewPipe environment ready.")
        }
    }

    override fun discoverCatalog() = ExtractorCatalog(YouTube).feed()
    override fun searchCatalog(query: String, page: Int) = ExtractorCatalog(YouTube).search(query)
    override fun getAlbum(id: String) = ExtractorCatalog(YouTube).album(id)
    override fun getArtist(id: String) = ExtractorCatalog(YouTube).artist(id)

    override fun discover(): List<com.indus.veena.contract.ExtDiscoverySection> {
        val kiosk = YouTube.kioskList.getExtractorById("trending_music", null)
        kiosk.fetchPage()
        val songs = kiosk.initialPage.items.filterIsInstance<StreamInfoItem>().map { item ->
            ExtSong(id = item.url, title = item.name, artist = item.uploaderName.orEmpty(),
                thumbnail = item.thumbnails.lastOrNull()?.url.orEmpty(), duration = item.duration.toString())
        }
        return listOf(com.indus.veena.contract.ExtDiscoverySection("trending_music", "Trending in music", songs,
            "What's taking off on YouTube"))
    }

    override fun getSuggestions(query: String): List<String> {
        return try {
            val suggestions = YouTube.suggestionExtractor.suggestionList(query)
            suggestions ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
            Log.d(TAG, "Searching for: $query")
            return try {
                val extractor = YouTube.getSearchExtractor(
                    query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), null
                )

                Log.d(TAG, "Fetching search page...")
                extractor.fetchPage()

                val items = extractor.initialPage.items
                Log.d(TAG, "Found ${items.size} total items on page.")

                val results = items.filterIsInstance<StreamInfoItem>()
                    .take(50)
                    .map { item ->
                        ExtSong(
                            id = item.url,
                            title = item.name ?: "Unknown",
                            artist = item.uploaderName ?: "Unknown Artist",
                            thumbnail = item.thumbnails.lastOrNull()?.url.orEmpty(),
                            duration = item.duration.toString(),
                            album = ""
                        )
                    }

                Log.d(TAG, "Mapped ${results.size} music tracks.")
                results
            } catch (t: Throwable) {
                Log.e(TAG, "CRITICAL: Search failed", t)
                emptyList()
            }
        }

    @Synchronized
    override fun getSongDetails(songId: String): ExtSong {
        try {
            return extractSongDetails(songId)
        } catch (error: Exception) {
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            val transient = causes.any { it is java.io.IOException ||
                it is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException }
            if (!transient) throw error
            Log.w(TAG, "First extraction failed; retrying once with a fresh extractor/visitor session", error)
            Thread.sleep(300)
            return extractSongDetails(songId)
        }
    }

    private fun extractSongDetails(songId: String): ExtSong {
        val absoluteUrl = when {
            songId.startsWith("http") -> songId
            songId.startsWith("/") -> "https://www.youtube.com$songId"
            else -> "https://www.youtube.com/watch?v=$songId"
        }

        Log.d(TAG, "Starting stream extraction process for URL: $absoluteUrl")

        try {
            val extractor = YouTube.getStreamExtractor(absoluteUrl)
            Log.d(TAG, "Extractor initialized. Fetching page layout...")
            extractor.fetchPage()
            Log.d(TAG, "Page layout fetch complete.")

            val audioStreams = extractor.audioStreams.filter { it.isUrl &&
                it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
            Log.d(TAG, "Total audio streams found: ${audioStreams.size}")
            val videoStreams = extractor.videoStreams.filter { it.isUrl &&
                it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
            Log.d(TAG, "Total video streams found: ${videoStreams.size}")
            val sortedAudio = audioStreams.sortedByDescending { it.bitrate }
            val playableURLs = mutableMapOf<String, String>()

            if (sortedAudio.isNotEmpty()) {
                playableURLs["320kbps"] = sortedAudio.first().content
                playableURLs["160kbps"] = sortedAudio.first().content
                if (sortedAudio.size > 1) {
                    playableURLs["128kbps"] = sortedAudio[sortedAudio.size / 2].content
                }
                playableURLs["48kbps"] = sortedAudio.last().content
                Log.d(TAG, "Successfully populated adaptive audio streams.")
            } else if (videoStreams.isNotEmpty()) {
                Log.w(TAG, "No adaptive audio streams returned. Deploying progressive video fallback pipeline...")
                val fallbackUrl = videoStreams.first().content
                playableURLs["320kbps"] = fallbackUrl
                playableURLs["160kbps"] = fallbackUrl
                playableURLs["128kbps"] = fallbackUrl
                playableURLs["48kbps"] = fallbackUrl
            } else {
                error("No progressive audio stream available; this track may require an updated extractor")
            }

            val rawDescription = extractor.description?.content ?: ""
            val composer = Regex("Composer:\\s*(.*)").find(rawDescription)?.groupValues?.get(1)?.trim() ?: ""
            val lyricist = Regex("Lyricist:\\s*(.*)").find(rawDescription)?.groupValues?.get(1)?.trim() ?: ""
            // Safe year resolution: prioritize regex, guard java.time reflection crash with Throwable
            var parsedYear = Regex("Released on:\\s*(\\d{4})").find(rawDescription)?.groupValues?.get(1) ?: ""
            if (parsedYear.isEmpty()) {
                parsedYear = try {
                    extractor.textualUploadDate?.takeLast(4)
                        ?: extractor.uploadDate?.instant?.toString()?.take(4)
                        ?: ""
                } catch (t: Throwable) {
                    // Catches NoSuchMethodError / OffsetDateTime desugar failures
                    ""
                }
            }

            var parsedAlbum = ""
            val blocks = rawDescription.split("<br>").map { it.trim() }.filter { it.isNotEmpty() }
            blocks.forEachIndexed { index, block ->
                if (block.contains("·") && index + 1 < blocks.size) {
                    val next = blocks[index + 1]
                    if (!next.startsWith("℗") && !next.startsWith("Released") && !next.startsWith("Composer")) {
                        parsedAlbum = next
                    }
                }
            }

            // Fixed: use track thumbnail instead of uploader profile avatar
            val trackThumbnail = extractor.thumbnails.lastOrNull()?.url
                ?: extractor.uploaderAvatars.firstOrNull()?.url?.replace("s48", "s720")

            return ExtSong(
                id = extractor.url,
                title = extractor.name,
                artist = extractor.uploaderName.removeSuffix(" - Topic"),
                thumbnail = trackThumbnail ?: "",
                duration = extractor.length.toString(),
                streamableUrls = playableURLs,
                album = parsedAlbum,
                year = parsedYear,
                composer = composer,
                lyricist = lyricist,
                genre = extractor.category ?: ""
            )
        } catch (e: org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException) {
            throw IllegalStateException("YouTube requires sign-in on this connection. Try another provider or try again later.", e)
        } catch (t: Throwable) {
            Log.e(TAG, "CRITICAL FAILURE: getSongDetails extraction crashed for ID: $songId", t)
            throw t
        }
    }

    override fun getStreamUrl(songId: String, quality: String): String {
        val details = getSongDetails(songId)
        return details.streamableUrls?.get(quality) ?: ""
    }
}
