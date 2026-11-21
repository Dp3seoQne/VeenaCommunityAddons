package com.veena.newpipe

import com.indus.veena.contract.*
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as Filters

/** Collection membership comes from extractor pages, never from a title-based song search. */
internal class ExtractorCatalog(private val service: StreamingService) {
    private fun song(item: StreamInfoItem) = ExtSong(item.url, item.name, item.uploaderName.orEmpty(),
        item.thumbnails.lastOrNull()?.url.orEmpty(), item.duration.toString())
    fun feed(): List<ExtCatalogSection> {
        val kiosks = service.kioskList
        val ids = if (service == ServiceList.YouTube) listOf("trending_music") else kiosks.availableKiosks.toList()
        return ids.mapNotNull { id ->
            try {
                val extractor = kiosks.getExtractorById(id, null)
                extractor.fetchPage()
                val streams = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
                val artists = streams.filter { !it.uploaderUrl.isNullOrBlank() }.distinctBy { it.uploaderUrl }
                    .map { ExtArtist(it.uploaderUrl, it.uploaderName.orEmpty(), it.uploaderAvatars?.lastOrNull()?.url.orEmpty()) }
                ExtCatalogSection(id, extractor.name, songs = streams.map(::song), artists = artists)
            } catch (e: Exception) {
                android.util.Log.w("VeenaCatalog", "Kiosk unavailable: $id", e)
                null
            }
        }.also { check(it.isNotEmpty()) { "No discovery sections were returned." } }
    }
    fun search(query: String): ExtSearchResults {
        if (service == ServiceList.YouTube) {
            fun items(filter: String): List<org.schabi.newpipe.extractor.InfoItem> {
                val extractor = service.getSearchExtractor(query, listOf(filter), null)
                extractor.fetchPage()
                return extractor.initialPage.items
            }
            // Separate filtered queries prevent unrelated videos from being presented as albums.
            val tracks = items(Filters.MUSIC_SONGS).filterIsInstance<StreamInfoItem>().map(::song)
            val albums = items(Filters.MUSIC_ALBUMS).filterIsInstance<PlaylistInfoItem>().map {
                ExtAlbum(it.url, it.name, it.uploaderName.orEmpty(), it.thumbnails.lastOrNull()?.url.orEmpty())
            }
            val artists = items(Filters.MUSIC_ARTISTS).filterIsInstance<ChannelInfoItem>().map {
                ExtArtist(it.url, it.name, it.thumbnails.lastOrNull()?.url.orEmpty())
            }
            return ExtSearchResults(tracks, albums, artists)
        }
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        val items = extractor.initialPage.items
        // The extractor does not identify SoundCloud album types. Expose supported artists only.
        val albums = emptyList<ExtAlbum>()
        return ExtSearchResults(items.filterIsInstance<StreamInfoItem>().map(::song), albums,
            items.filterIsInstance<ChannelInfoItem>().map { ExtArtist(it.url, it.name, it.thumbnails.lastOrNull()?.url.orEmpty()) })
    }
    fun album(id: String): ExtCollection {
        val extractor = service.getPlaylistExtractor(id)
        extractor.fetchPage()
        return ExtCollection(extractor.name, extractor.uploaderName.orEmpty(), extractor.thumbnails.lastOrNull()?.url.orEmpty(),
            extractor.description?.content.orEmpty(), extractor.initialPage.items.map(::song))
    }
    fun artist(id: String): ExtCollection {
        val channel = service.getChannelExtractor(id)
        channel.fetchPage()
        val tabs = channel.tabs
        val tab = tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.TRACKS) }
            ?: tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return ExtCollection(channel.name, thumbnail = channel.avatars.lastOrNull()?.url.orEmpty(), description = channel.description.orEmpty())
        val extractor = service.getChannelTabExtractor(tab)
        extractor.fetchPage()
        return ExtCollection(channel.name, thumbnail = channel.avatars.lastOrNull()?.url.orEmpty(),
            description = channel.description.orEmpty(), songs = extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::song))
    }
}
