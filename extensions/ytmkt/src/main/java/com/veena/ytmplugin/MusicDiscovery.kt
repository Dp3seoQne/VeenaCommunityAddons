package com.veena.ytmplugin

import com.indus.veena.contract.ExtDiscoverySection
import com.indus.veena.contract.*
import com.indus.veena.contract.ExtensionHost
import org.json.JSONArray
import org.json.JSONObject

/** Parse the curated Explore shelves, resolving album cards to their actual tracks. */
internal class MusicDiscovery(private val host: ExtensionHost) {
    private fun request(endpoint: String, fields: JSONObject): JSONObject {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20261019.01.00")
            .put("hl", "en").put("gl", java.util.Locale.getDefault().country.ifBlank { "IN" })
        val body = fields.put("context", JSONObject().put("client", client))
        val response = host.httpPostFull("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", body.toString(),
            headers = mapOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"))
        check(response.code in 200..299) { "YouTube Music discovery returned HTTP ${response.code}" }
        return JSONObject(response.body)
    }

    private fun browse(id: String) = request("browse", JSONObject().put("browseId", id))
    private fun cards(root: Any?): Pair<List<ExtAlbum>, List<ExtArtist>> {
        val albums = mutableListOf<ExtAlbum>()
        val artists = mutableListOf<ExtArtist>()
        (nodes(root, "musicTwoRowItemRenderer") + nodes(root, "musicResponsiveListItemRenderer")).forEach { row ->
            val endpoint = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: nodes(row.optJSONObject("title"), "browseEndpoint").firstOrNull()
                ?: nodes(row.optJSONArray("flexColumns")?.optJSONObject(0), "browseEndpoint").firstOrNull()
            val id = endpoint?.optString("browseId").orEmpty()
            val title = text(row.optJSONObject("title")).ifBlank { columnText(row.optJSONArray("flexColumns"), 0) }
            val subtitle = text(row.optJSONObject("subtitle")).ifBlank { columnText(row.optJSONArray("flexColumns"), 1) }
            if (title.isNotBlank()) when {
                id.startsWith("MPRE") -> albums += ExtAlbum(id, title, subtitle, artwork(row))
                id.startsWith("UC") -> artists += ExtArtist(id, title, artwork(row))
            }
        }
        return albums.distinctBy { it.id } to artists.distinctBy { it.id }
    }
    fun catalog(): List<ExtCatalogSection> {
        val root = browse("FEmusic_explore")
        return nodes(root, "musicCarouselShelfRenderer").mapIndexedNotNull { index, shelf ->
            val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val title = text(header?.optJSONObject("title")).ifBlank { "Explore music" }
            val contents = shelf.optJSONArray("contents")
            val (albums, artists) = cards(contents)
            val tracks = songs(contents)
            if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()) null
            else ExtCatalogSection("explore:$index", title, songs = tracks, albums = albums, artists = artists)
        }.also { check(it.isNotEmpty()) { "No discovery sections were returned." } }
    }
    fun search(query: String): ExtSearchResults {
        val root = request("search", JSONObject().put("query", query))
        val (albums, artists) = cards(root)
        return ExtSearchResults(songs = songs(root), albums = albums, artists = artists)
    }
    fun collection(id: String): ExtCollection {
        val root = browse(id)
        val header = nodes(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: nodes(root, "musicDetailHeaderRenderer").firstOrNull()
            ?: nodes(root, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: nodes(root, "musicVisualHeaderRenderer").firstOrNull()
        val title = text(header?.optJSONObject("title"))
        val subtitle = text(header?.optJSONObject("straplineTextOne")).ifBlank { text(header?.optJSONObject("subtitle")) }
        val image = artwork(header)
        return ExtCollection(title, subtitle, image, text(header?.optJSONObject("description")),
            songs(root).map { it.copy(thumbnail = it.thumbnail.ifBlank { image }) })
    }

    fun discover(): List<ExtDiscoverySection> {
        val root = browse("FEmusic_explore")
        val sections = mutableListOf<ExtDiscoverySection>()
        nodes(root, "musicCarouselShelfRenderer").forEachIndexed { index, shelf ->
            val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val title = text(header?.optJSONObject("title")).ifBlank { "Explore music" }
            val content = shelf.optJSONArray("contents") ?: return@forEachIndexed
            val direct = songs(content)
            if (direct.isNotEmpty()) {
                sections += ExtDiscoverySection("explore:$index", title, direct, "YouTube Music")
            } else {
                // A bounded number of album/playlist requests keeps cold discovery inexpensive.
                nodes(content, "musicTwoRowItemRenderer").mapNotNull { item ->
                    val endpoint = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        ?: nodes(item.optJSONObject("title"), "browseEndpoint").firstOrNull()
                    val id = endpoint?.optString("browseId").orEmpty()
                    if (id.startsWith("MPRE") || id.startsWith("VL")) id else null
                }.distinct().take(2).forEach { id ->
                    try {
                        val albumPage = browse(id)
                        val albumHeader = nodes(albumPage, "musicResponsiveHeaderRenderer").firstOrNull()
                            ?: nodes(albumPage, "musicDetailHeaderRenderer").firstOrNull()
                        val albumTitle = text(albumHeader?.optJSONObject("title"))
                        val albumArtist = text(albumHeader?.optJSONObject("straplineTextOne"))
                        val artwork = artwork(albumHeader)
                        val tracks = songs(albumPage).map { track -> track.copy(
                            album = albumTitle, artist = track.artist.ifBlank { albumArtist },
                            thumbnail = track.thumbnail.ifBlank { artwork }) }
                        if (tracks.isNotEmpty()) sections += ExtDiscoverySection("album:$id", title, tracks, albumTitle)
                    } catch (e: Exception) { android.util.Log.w("VeenaYtm", "Explore collection unavailable", e) }
                }
            }
        }
        check(sections.isNotEmpty()) { "No music discovery sections were returned. Try again shortly." }
        return sections.distinctBy { it.id }
    }

    private fun songs(value: Any?): List<ExtSong> {
        val rows = nodes(value, "musicResponsiveListItemRenderer") + nodes(value, "musicTwoRowItemRenderer")
        return rows.mapNotNull { row ->
            val endpoint = nodes(row, "watchEndpoint").firstOrNull()
            val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: endpoint?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
                ?: return@mapNotNull null
            val columns = row.optJSONArray("flexColumns")
            val title = text(row.optJSONObject("title")).ifBlank { columnText(columns, 0) }
            if (title.isBlank()) return@mapNotNull null
            val subtitle = row.optJSONObject("subtitle")
            val subtitleText = columnText(columns, 1).ifBlank { text(subtitle) }
            val artistNames = mutableListOf<String>()
            fun findArtists(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val id = value.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                        if (id.startsWith("UC")) value.optString("text").takeIf { it.isNotBlank() }?.let { artistNames += it }
                        value.keys().forEach { findArtists(value.opt(it)) }
                    }
                    is JSONArray -> for (i in 0 until value.length()) findArtists(value.opt(i))
                }
            }
            findArtists(columns ?: subtitle)
            val thumbnail = artwork(row.optJSONObject("thumbnail") ?: row.optJSONObject("thumbnailRenderer"))
                .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
            val durationText = columnText(row.optJSONArray("fixedColumns"), 0)
            val seconds = durationText.split(":").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }
                ?.fold(0L) { total, part -> total * 60 + part } ?: 0L
            ExtSong(id = videoId, title = title,
                artist = artistNames.distinct().joinToString(", ").ifBlank { subtitleText },
                thumbnail = thumbnail, duration = seconds.toString(), url = "https://music.youtube.com/watch?v=$videoId")
        }.distinctBy { it.id }.take(50)
    }

    private fun artwork(value: Any?): String {
        when (value) {
            is JSONObject -> {
                value.optJSONArray("thumbnails")?.let { list ->
                    for (i in list.length() - 1 downTo 0) {
                        val url = list.optJSONObject(i)?.optString("url").orEmpty()
                        if (url.isNotBlank()) return url
                    }
                }
                value.keys().forEach { key -> artwork(value.opt(key)).takeIf { it.isNotBlank() }?.let { return it } }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                artwork(value.opt(i)).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun columnText(columns: JSONArray?, index: Int) = text(columns?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        ?: columns?.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"))

    private fun text(value: JSONObject?): String {
        val runs = value?.optJSONArray("runs") ?: return value?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun nodes(value: Any?, key: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject(key)?.let { result += it; return }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
            }
        }
        visit(value)
        return result
    }
}
