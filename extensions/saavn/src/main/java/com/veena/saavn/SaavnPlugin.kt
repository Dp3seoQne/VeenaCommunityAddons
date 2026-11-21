package com.veena.saavn

import android.annotation.SuppressLint
import android.util.Base64
import com.indus.veena.contract.AddonEntryPoint
import com.indus.veena.contract.ExtSong
import com.indus.veena.contract.ExtensionHost
import com.indus.veena.contract.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@AddonEntryPoint
class SaavnPlugin : com.indus.veena.contract.CatalogAddon, PagedArtistAddon {

    private lateinit var host: ExtensionHost
    private val json = Json { ignoreUnknownKeys = true }

    override fun discover(): List<com.indus.veena.contract.ExtDiscoverySection> {
        val base = "https://www.jiosaavn.com/api.php?_format=json&_marker=0&ctx=web6dot0"
        val launch = org.json.JSONObject(host.httpGet("$base&__call=webapi.getLaunchData"))
        val sections = mutableListOf<com.indus.veena.contract.ExtDiscoverySection>()
        fun addCollection(id: String, title: String, call: String, parameter: String, value: String) {
            if (value.isBlank()) return
            try {
                val response = host.httpGet("$base&__call=$call&$parameter=$value")
                val root = org.json.JSONObject(response)
                val songs = root.optJSONArray("songs") ?: root.optJSONArray("list") ?: return
                val mapped = (0 until songs.length()).map { i ->
                    val item = songs.getJSONObject(i)
                    val info = item.optJSONObject("more_info")
                    ExtSong(id = item.optString("id"),
                        title = item.optString("song", item.optString("title")).sanitize(),
                        artist = item.optString("primary_artists", item.optString("singers", item.optString("subtitle"))).sanitize(),
                        thumbnail = item.optString("image").replace("150x150", "500x500"),
                        duration = item.optString("duration", info?.optString("duration") ?: "0"),
                        album = item.optString("album", info?.optString("album") ?: "").sanitize(),
                        year = item.optString("year"))
                }.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(50)
                if (mapped.isNotEmpty()) sections += com.indus.veena.contract.ExtDiscoverySection(id, title.sanitize(), mapped)
            } catch (e: Exception) {
                android.util.Log.w("VeenaSaavn", "Discovery section $id unavailable", e)
            }
        }
        val trending = launch.optJSONArray("new_trending")
        for (i in 0 until minOf(trending?.length() ?: 0, 2)) {
            val item = trending!!.getJSONObject(i)
            val details = item.optJSONObject("details") ?: item
            addCollection("trending:$i", details.optString("listname", "Trending now"),
                "playlist.getDetails", "listid", details.optString("listid"))
        }
        val charts = launch.optJSONArray("charts")
        for (i in 0 until minOf(charts?.length() ?: 0, 2)) {
            val chart = charts!!.getJSONObject(i)
            addCollection("chart:$i", chart.optString("title", "Top charts"), "playlist.getDetails", "listid", chart.optString("id"))
        }
        val albums = launch.optJSONArray("new_albums")
        for (i in 0 until minOf(albums?.length() ?: 0, 3)) {
            val album = albums!!.getJSONObject(i)
            addCollection("album:$i", "New release · ${album.optString("title")}", "content.getAlbumDetails", "albumid", album.optString("albumid"))
        }
        check(sections.isNotEmpty()) { "Saavn discovery is unavailable. Try again shortly." }
        return sections
    }

    private fun api(call: String, parameter: String = "", value: String = "", page: Int = 1): JSONObject {
        val encoded = java.net.URLEncoder.encode(value, "UTF-8")
        return JSONObject(host.httpGet("https://www.jiosaavn.com/api.php?_format=json&_marker=0&ctx=web6dot0&__call=$call&$parameter=$encoded&p=$page"))
    }
    private fun objects(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    private fun catalogSong(item: JSONObject): ExtSong {
        val info = item.optJSONObject("more_info")
        val artists = objects(info?.optJSONObject("artistMap")?.optJSONArray("primary_artists"))
            .joinToString(", ") { it.optString("name") }.sanitize()
        return ExtSong(id = item.optString("id"), title = item.optString("song", item.optString("title")).sanitize(),
            artist = artists.ifBlank { item.optString("primary_artists", item.optString("subtitle", item.optString("singers"))).sanitize() },
            thumbnail = item.optString("image").replace("150x150", "500x500"),
            duration = item.optString("duration", info?.optString("duration") ?: "0"),
            album = item.optString("album", info?.optString("album") ?: "").sanitize(), year = item.optString("year"))
    }
    private fun album(item: JSONObject) = ExtAlbum(
        id = item.optString("albumid").ifBlank { item.optString("id") },
        title = item.optString("title", item.optString("name")).sanitize(),
        artist = item.optString("subtitle").ifBlank { item.optString("primary_artists") }.sanitize(),
        thumbnail = item.optString("image").replace("150x150", "500x500"), year = item.optString("year"))
    override fun discoverCatalog(): List<ExtCatalogSection> {
        val launch = api("webapi.getLaunchData")
        val albums = objects(launch.optJSONArray("new_albums")).map(::album).filter { it.id.isNotBlank() }
        val artists = objects(launch.optJSONArray("artist_recos")).map {
            ExtArtist(it.optString("id"), it.optString("title", it.optString("name")).sanitize(), it.optString("image").replace("150x150", "500x500").replace("50x50", "500x500"))
        }.filter { it.id.isNotBlank() }
        val sections = discover().map { ExtCatalogSection(it.id, it.title, it.subtitle, songs = it.songs) }.toMutableList()
        if (albums.isNotEmpty()) sections += ExtCatalogSection("new-albums", "New albums", albums = albums)
        if (artists.isNotEmpty()) sections += ExtCatalogSection("artists", "Artists to explore", artists = artists)
        return sections
    }
    override fun searchCatalog(query: String, page: Int): ExtSearchResults {
        val result = api("search.getResults", "q", query, page)
        val albums = api("search.getAlbumResults", "q", query, page)
        val artists = api("search.getArtistResults", "q", query, page)
        return ExtSearchResults(songs = objects(result.optJSONArray("results")).map(::catalogSong).take(50),
            albums = objects(albums.optJSONArray("results")).map(::album),
            artists = objects(artists.optJSONArray("results")).map {
                ExtArtist(it.optString("id"), it.optString("name", it.optString("title")).sanitize(), it.optString("image").replace("150x150", "500x500").replace("50x50", "500x500"))
            })
    }
    override fun getAlbum(id: String): ExtCollection {
        val root = api("content.getAlbumDetails", "albumid", id)
        return ExtCollection(root.optString("title", root.optString("name")), root.optString("primary_artists"),
            root.optString("image"), songs = objects(root.optJSONArray("songs") ?: root.optJSONArray("list")).map(::catalogSong))
    }
    override fun getArtist(id: String): ExtCollection {
        val root = api("artist.getArtistPageDetails", "artistId", id)
        return ExtCollection(root.optString("name"), root.optString("subtitle"), root.optString("image").replace("150x150", "500x500").replace("50x50", "500x500"),
            description = objects(root.optJSONArray("bio")).joinToString("\n\n") { it.optString("text") }.sanitize(),
            songs = objects(root.optJSONArray("topSongs")).map(::catalogSong))
    }

    override fun getArtistSongs(id: String, page: Int): ExtArtistSongPage {
        val encoded = java.net.URLEncoder.encode(id, "UTF-8")
        val root = JSONObject(host.httpGet("https://www.jiosaavn.com/api.php?_format=json&_marker=0&ctx=web6dot0&__call=artist.getArtistMoreSong&artistId=$encoded&page=$page"))
        val result = root.optJSONObject("topSongs") ?: error("Artist songs unavailable")
        val songs = objects(result.optJSONArray("songs")).map(::catalogSong).filter { it.id.isNotBlank() }
        return ExtArtistSongPage(songs, songs.isNotEmpty() && !result.optBoolean("last_page", true))
    }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
        val url = "https://www.saavn.com/api.php?__call=search.getResults&q=${java.net.URLEncoder.encode(query, "UTF-8")}&p=$page&_format=json&ctx=wap6dot0"
        val response = host.httpGet(url)
        val root = json.decodeFromString<SaavnRoot>(response)
        return root.results?.map { it.toExtSong() }?.take(50) ?: emptyList()
    }

    override fun onLoad(host: ExtensionHost) {
        this.host = host
    }

    override fun getSongDetails(songId: String): ExtSong {
        val url = "https://www.saavn.com/api.php?__call=song.getDetails&pids=$songId&_format=json"
        val response = host.httpGet(url)
        val resMap = json.decodeFromString<Map<String, SaavnSearchResult>>(response)
        val data = resMap[songId] ?: throw Exception("Song not found.")

        return data.toExtSong().copy(
            streamableUrls = decryptUrls(data.encryptedMediaUrl ?: "")
        )
    }

    override fun getStreamUrl(songId: String, quality: String): String {
        val details = getSongDetails(songId)
        return details.streamableUrls?.get(quality) ?: ""
    }

    @SuppressLint("GetInstance")
    private fun decryptUrls(payload: String): Map<String, String> {
        if (payload.isBlank()) return emptyMap()
        try {
            val key = "38346591"
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "DES"))

            val decodedBytes = Base64.decode(payload, Base64.DEFAULT)
            val decrypted = String(cipher.doFinal(decodedBytes), Charsets.UTF_8)

            val endOfUrl = decrypted.indexOf(".mp4")
            if (endOfUrl == -1) return emptyMap()

            val cleanUrl = decrypted.substring(0, endOfUrl + 4)
            val urls = mapOf(
                "12kbps" to cleanUrl.replace("_96", "_12"),
                "48kbps" to cleanUrl.replace("_96", "_48"),
                "96kbps" to cleanUrl.replace("_96", "_96"),
                "160kbps" to cleanUrl.replace("_96", "_160"),
                "320kbps" to cleanUrl.replace("_96", "_320")
            )

            return urls
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    private fun SaavnSearchResult.toExtSong(): ExtSong {
        return ExtSong(
            id = this.id ?: "",
            title = this.song.sanitize(),
            artist = this.primaryArtists ?: this.singers ?: "",
            thumbnail = this.image?.replace("150x150", "500x500") ?: "",
            duration = this.duration ?: "0",
            album = this.album.sanitize(),
            composer = this.music ?: "",
            genre = this.language ?: "",
            year = this.year ?: ""
        )
    }

    private fun String?.sanitize(): String {
        if (this.isNullOrEmpty()) return ""
        val entities = mapOf(
            "&quot;" to "",
            "&amp;" to "&"
        )
        val regex = entities.keys.joinToString("|") { Regex.escape(it) }.toRegex()
        return regex.replace(this) { matchResult ->
            entities[matchResult.value] ?: matchResult.value
        }
    }

    @Serializable
    private data class SaavnRoot(val results: List<SaavnSearchResult>? = null)

    @Serializable
    private data class SaavnSearchResult(
        val id: String? = null,
        val song: String? = null,
        val album: String? = null,
        val year: String? = null,
        val music: String? = null,
        @SerialName("primary_artists") val primaryArtists: String? = null,
        val singers: String? = null,
        val image: String? = null,
        val language: String? = null,
        @SerialName("encrypted_media_url") val encryptedMediaUrl: String? = null,
        val duration: String? = null
    )
}
