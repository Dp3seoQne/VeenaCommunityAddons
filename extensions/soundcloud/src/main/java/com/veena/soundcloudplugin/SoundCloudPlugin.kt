package com.veena.soundcloudplugin

import com.indus.veena.contract.AddonEntryPoint
import com.indus.veena.contract.ExtSong
import com.indus.veena.contract.ExtensionHost
import com.indus.veena.contract.MusicAddon
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.SoundCloud
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@AddonEntryPoint
class Plugin : MusicAddon {
    private lateinit var host: ExtensionHost
    private val TAG = "VEENA_SC_PLUGIN"

    private var clientId: String? = null
    private val apiBaseUrl = "https://api-v2.soundcloud.com"

    private val scriptRegex = Regex("""<script\s+crossorigin\s+src="([^"]+)"""")
    private val clientIdRegex = Regex("""client_id\s*:\s*"(\w+)"""")

    init {
        try {
            NewPipe.init(object : Downloader() {
                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                    val url = request.url()
                    val httpMethod = request.httpMethod()
                    val data = request.dataToSend()
                    val headers = request.headers().mapValues { it.value.firstOrNull() ?: "" }

                    val res = if (httpMethod == "POST") {
                        host.httpPostFull(url, data?.let { String(it) } ?: "", "application/json", headers)
                    } else {
                        host.httpGetFull(url, headers)
                    }

                    return org.schabi.newpipe.extractor.downloader.Response(
                        res.code, "", res.headers, res.body, res.finalUrl
                    )
                }
            }
            )
        } catch (e: Exception) { /* already init */ }
    }

    override fun onLoad(host: ExtensionHost) {
        this.host = host
    }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
        return try {
            val extractor = SoundCloud.getSearchExtractor(query)
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map { item ->
                ExtSong(
                    id = item.url,
                    title = item.name ?: "Unknown",
                    artist = item.uploaderName ?: "Unknown Artist",
                    thumbnail = item.thumbnails.lastOrNull()?.url ?: "",
                    duration = item.duration.toString(),
                    album = ""
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    override fun getSuggestions(query: String): List<String> {
        return try {
            val suggestions = SoundCloud.suggestionExtractor.suggestionList(query)
            suggestions ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getSongDetails(songId: String): ExtSong {
        ensureTokens()

        val resolveUrl = "$apiBaseUrl/resolve?url=$songId&client_id=$clientId"
        val trackResponse = host.httpGet(resolveUrl)
        val trackObj = JSONObject(trackResponse)
        val playableURLs = mutableMapOf<String, String>()

        val transcodings = trackObj.optJSONObject("media")?.optJSONArray("transcodings")
        if (transcodings != null) {
            for (i in 0 until transcodings.length()) {
                val tc = transcodings.getJSONObject(i)
                val apiUrl = tc.optString("url")
                val format = tc.optJSONObject("format")
                if (apiUrl.isNotEmpty()) {
                    try {
                        val streamJson = host.httpGet("$apiUrl?client_id=$clientId")
                        val streamUrl = JSONObject(streamJson).optString("url")
                        if (streamUrl.isNotEmpty()) {
                            val quality = if (format?.optString("mime_type")?.contains("ogg") == true) "160kbps" else "128kbps"
                            playableURLs[quality] = streamUrl
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return ExtSong(
            id = songId,
            title = trackObj.optString("title", "Unknown"),
            artist = trackObj.optJSONObject("user")?.optString("username", "Unknown Artist") ?: "Unknown Artist",
            thumbnail = trackObj.optString("artwork_url").replace("large", "t500x500"),
            duration = trackObj.optLong("duration", 0).toString(),
            streamableUrls = playableURLs,
            genre = trackObj.optString("genre", "Music"),
            year = trackObj.optString("created_at").take(4)
        )
    }

    override fun getStreamUrl(songId: String, quality: String): String {
        val details = getSongDetails(songId)
        return details.streamableUrls?.get(quality) ?: ""
    }

    private fun ensureTokens() {
        if (!clientId.isNullOrEmpty()) return
        val homePage = host.httpGet("https://soundcloud.com")
        val scriptUrls = scriptRegex.findAll(homePage).map { it.groupValues[1] }.toList()
        for (url in scriptUrls.reversed()) {
            val content = host.httpGet(url)
            clientIdRegex.find(content)?.let {
                clientId = it.groupValues[1]
                return
            }
        }
    }
}
