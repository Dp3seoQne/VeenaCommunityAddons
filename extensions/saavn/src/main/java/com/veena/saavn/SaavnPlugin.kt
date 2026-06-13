package com.veena.saavn

import android.annotation.SuppressLint
import android.util.Base64
import com.indus.veena.contract.AddonEntryPoint
import com.indus.veena.contract.ExtSong
import com.indus.veena.contract.ExtensionHost
import com.indus.veena.contract.MusicAddon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@AddonEntryPoint
class SaavnPlugin : MusicAddon {

    private lateinit var host: ExtensionHost
    private val json = Json { ignoreUnknownKeys = true }

    override fun searchSongs(query: String, page: Int): List<ExtSong> {
        val url = "https://www.saavn.com/api.php?__call=search.getResults&q=$query&p=$page&_format=json&ctx=wap6dot0"
        val response = host.httpGet(url)
        val root = json.decodeFromString<SaavnRoot>(response)
        return root.results?.map { it.toExtSong() } ?: emptyList()
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