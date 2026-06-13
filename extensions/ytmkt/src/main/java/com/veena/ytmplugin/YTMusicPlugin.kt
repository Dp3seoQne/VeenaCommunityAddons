package com.veena.ytmplugin

import com.indus.veena.contract.AddonEntryPoint
import com.indus.veena.contract.ExtSong
import com.indus.veena.contract.ExtensionHost
import com.indus.veena.contract.MusicAddon
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.itemcache.MediaItemCache
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong

@AddonEntryPoint
class Plugin : MusicAddon {
    private lateinit var host: ExtensionHost
    private val api by lazy {
        YoutubeiApi(
            dataLocale = sh.syk.kmpresources.library.model.Locale.DEFAULT,
            apiUrl = "https://music.youtube.com/youtubei/v1/",
            nonMusicApiUrl = "https://www.youtube.com/youtubei/v1/",
            itemCache = MediaItemCache()
        )
    }
    private val SONG_FILTER = "EgWKAQIIAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D"

    override fun onLoad(host: ExtensionHost) {
        this.host = host
    }

    override fun getSuggestions(query: String): List<String> {
        return try {
            host.runSuspending {
                val ytMusicEndpoint = YoutubeiApi().SearchSuggestions
                val suggestions = ytMusicEndpoint.getSearchSuggestions(query).getOrNull()
                suggestions?.map { it.text } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
        return try {
            host.runSuspending {
                val result = api.Search.search(query, SONG_FILTER, false).getOrThrow()
                result.categories.flatMap { it.first.items.filterIsInstance<YtmSong>() }
                    .map { it.toExtSong() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getSongDetails(songId: String): ExtSong {
        val playableURLs = mutableMapOf<String, String>()
        try {
            host.runSuspending {
                val videoFormats = api.VideoFormats.getVideoFormats(songId).getOrThrow()
                videoFormats.forEach { format ->
                    when (format.itag) {
                        139 -> playableURLs["48kbps"] = format.url!!
                        249 -> playableURLs["53kbps"] = format.url!!
                        250 -> playableURLs["64kbps"] = format.url!!
                        140 -> playableURLs["128kbps"] = format.url!!
                        251 -> playableURLs["160kbps"] = format.url!!
                        141 -> playableURLs["256kbps"] = format.url!!
                    }
                }
            }
        } catch (e: Exception) { }

        return ExtSong(
            id = songId,
            title = "",
            artist = "",
            thumbnail = "",
            duration = "0",
            streamableUrls = playableURLs
        )
    }

    override fun getStreamUrl(songId: String, quality: String): String {
        val details = getSongDetails(songId)
        return details.streamableUrls?.get(quality) ?: ""
    }

    private fun YtmSong.toExtSong() = ExtSong(
        id = this.id,
        title = this.name ?: "Unknown",
        artist = this.artists?.firstOrNull()?.name ?: "Unknown Artist",
        thumbnail = this.thumbnail_provider?.getThumbnailUrl(ThumbnailProvider.Quality.HIGH) ?: "",
        duration = this.duration?.toString() ?: "0",
        album = this.album?.name ?: ""
    )
}