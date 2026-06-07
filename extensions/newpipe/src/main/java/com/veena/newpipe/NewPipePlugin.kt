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
class Plugin : MusicAddon {
    private lateinit var host: ExtensionHost
    private val TAG = "VEENA_NEWPIPE_PLUGIN"

    init {
        try {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "NewPipe downloader failed for $url", e)
                        throw e
                    }
                }
            }
            )

            Log.d(TAG, "NewPipe environment ready.")
        } catch (e: Exception) {
            // This fires if NewPipe was already init'd in a previous load — that's fine
            Log.w(TAG, "NewPipe init skipped or failed: ${e.message}")
        }
    }

    override fun onLoad(host: ExtensionHost) {
        this.host = host
    }

    override fun getSuggestions(query: String): List<String> {
        return try {
            val suggestions = YouTube.suggestionExtractor.suggestionList(query)
            suggestions ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
        //withContext(Dispatchers.IO) {
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
                    .take(20)
                    .map { item ->
                        ExtSong(
                            id = item.url,
                            title = item.name ?: "Unknown",
                            artist = item.uploaderName ?: "Unknown Artist",
                            thumbnail = item.thumbnails.lastOrNull()?.url?.let { url ->
                                if (url.contains("=")) {
                                    url.replaceAfterLast("=", "w720-h720")
                                } else url
                            } ?: "",
                            duration = item.duration.toString(),
                            album = ""
                        )
                    }

                Log.d(TAG, "Mapped ${results.size} music tracks.")
                results
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Search failed", e)
                emptyList()
            }
        }

    override fun getSongDetails(songId: String): ExtSong {
        val absoluteUrl = when {
            songId.startsWith("http") -> songId
            songId.startsWith("/") -> "https://www.youtube.com$songId"
            else -> "https://www.youtube.com/watch?v=$songId"
        }

        val extractor = YouTube.getStreamExtractor(absoluteUrl)
        extractor.fetchPage()

        val audioStreams = extractor.audioStreams.sortedByDescending { it.bitrate }
        val playableURLs = mutableMapOf<String, String>()

        if (audioStreams.isNotEmpty()) {
            playableURLs["320kbps"] = audioStreams.first().content
            playableURLs["160kbps"] = audioStreams.first().content
            if (audioStreams.size > 1) {
                playableURLs["128kbps"] = audioStreams[audioStreams.size / 2].content
            }
            playableURLs["48kbps"] = audioStreams.last().content
        }

        val rawDescription = extractor.description.content
        val composer = Regex("Composer:\\s*(.*)").find(rawDescription)?.groupValues?.get(1)?.trim() ?: ""
        val lyricist = Regex("Lyricist:\\s*(.*)").find(rawDescription)?.groupValues?.get(1)?.trim() ?: ""
        var parsedYear = ""

        val releasedOnMatch = Regex("Released on:\\s*(\\d{4})").find(rawDescription)
        if (releasedOnMatch != null) {
            parsedYear = releasedOnMatch.groupValues[1]
        }

        if (parsedYear.isEmpty()) {
            parsedYear = extractor.uploadDate?.instant?.toString()?.take(4) ?: ""
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

        return ExtSong(
            id = extractor.url,
            title = extractor.name,
            artist = extractor.uploaderName.removeSuffix(" - Topic"),
            thumbnail = extractor.uploaderAvatars.firstOrNull()?.url?.replace("s48", "s720") ?: "",
            duration = extractor.length.toString(),
            streamableUrls = playableURLs,
            album = parsedAlbum,
            year = parsedYear,
            composer = composer,
            lyricist = lyricist,
            genre = extractor.category
        )
    }

    override fun getStreamUrl(songId: String, quality: String): String {
        val details = getSongDetails(songId)
        return details.streamableUrls?.get(quality) ?: ""
    }
}
