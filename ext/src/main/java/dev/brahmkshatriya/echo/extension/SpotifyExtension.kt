package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ArtistFollowClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveToLibraryClient
import dev.brahmkshatriya.echo.common.clients.SearchClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackHideClient
import dev.brahmkshatriya.echo.common.clients.TrackLikeClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.spotify.Base62
import dev.brahmkshatriya.echo.extension.spotify.Queries
import dev.brahmkshatriya.echo.extension.spotify.SpotifyApi
import dev.brahmkshatriya.echo.extension.spotify.SpotifyApi.Companion.userAgent
import dev.brahmkshatriya.echo.extension.spotify.models.AccountAttributes
import dev.brahmkshatriya.echo.extension.spotify.models.ArtistOverview
import dev.brahmkshatriya.echo.extension.spotify.models.GetAlbum
import dev.brahmkshatriya.echo.extension.spotify.models.Item
import dev.brahmkshatriya.echo.extension.spotify.models.UserProfileView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpCookie

class SpotifyExtension : ExtensionClient, LoginClient.WebView.Cookie,
    SearchClient, HomeFeedClient, LibraryClient, LyricsClient, ShareClient,
    TrackClient, TrackLikeClient, TrackHideClient, RadioClient, SaveToLibraryClient,
    AlbumClient, PlaylistClient, ArtistClient, ArtistFollowClient {

    override suspend fun onExtensionSelected() {}
    override val settingItems: List<Setting> = emptyList()


    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val api by lazy {
        SpotifyApi(setting.toCache()) {
            val token = token
            if (it.code == 401 && token != null) throw ClientException.Unauthorized(token)
            else throw it
        }
    }
    val queries by lazy { Queries(api) }

    override val loginWebViewInitialUrl =
        "https://accounts.spotify.com/en/login".toRequest(mapOf(userAgent))

    override val loginWebViewStopUrlRegex = "https://accounts\\.spotify\\.com/en/status".toRegex()

    override suspend fun onLoginWebviewStop(url: String, data: String): List<User> {
        val parsed = data.split(";").mapNotNull {
            if (it.isBlank()) null else HttpCookie.parse(it).firstOrNull()
        }
        val token = parsed.find { it.name == "sp_dc" } ?: throw Exception("Token not found")
        api.token = token.value
        val user = getCurrentUser()!!.copy(id = token.value)
        return listOf(user)
    }

    override suspend fun onSetLoginUser(user: User?) {
        api.token = user?.id
    }

    private var user: User? = null
    override suspend fun getCurrentUser(): User? {
        if (user == null)
            user = if (api.token == null) null else queries.profileAttributes().json.toUser()
        return user
    }

    private var product: AccountAttributes.Product? = null
    private suspend fun hasPremium(): Boolean {
        if (api.token == null) return false
        if (product == null) product = queries.accountAttributes().json.data.me.account.product
        return product != AccountAttributes.Product.FREE
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}
    override suspend fun quickSearch(query: String?): List<QuickSearchItem> {
        if (query.isNullOrBlank()) return emptyList()
        val results = queries.searchDesktop(query, 10).json.data.searchV2.topResultsV2?.itemsV2
        return results?.mapNotNull {
            val item = it.item?.toMediaItem() ?: return@mapNotNull null
            QuickSearchItem.Media(item, false)
        }.orEmpty()
    }

    private var oldSearch: PagedData<Shelf>? = null
    private fun getBrowsePage(): PagedData<Shelf> = PagedData.Single {
        queries.browseAll().json.data.browseStart.sections.toShelves(queries)
    }

    override fun searchFeed(query: String?, tab: Tab?): PagedData<Shelf> {
        if (query == null) return getBrowsePage()
        if (tab == null || tab.id == "ALL") return oldSearch ?: getBrowsePage()
        return paged { offset ->
            when (tab.id) {
                "ARTISTS" -> queries.searchArtist(query, offset).json.data.searchV2.artists
                    .toItemShelves()

                "TRACKS" -> queries.searchTrack(query, offset).json.data.searchV2.tracksV2
                    .toItemShelves()

                "ALBUMS" -> queries.searchAlbum(query, offset).json.data.searchV2.albumsV2
                    .toItemShelves()

                "PLAYLISTS" -> queries.searchPlaylist(query, offset).json.data.searchV2.playlists
                    .toItemShelves()

                "GENRES" -> queries.searchGenres(query, offset).json.data.searchV2.genres
                    .toCategoryShelves(queries)

                "EPISODES" -> queries.searchFullEpisodes(query, offset).json.data.searchV2.episodes
                    .toItemShelves()

                "USERS" -> queries.searchUser(query, offset).json.data.searchV2.users
                    .toItemShelves()

                else -> emptyList<Shelf>() to null
            }
        }
    }

    override suspend fun searchTabs(query: String?): List<Tab> {
        if (query.isNullOrBlank()) return emptyList()
        val (shelves, tabs) = queries.searchDesktop(query).json.data.searchV2
            .toShelvesAndTabs(queries)
        oldSearch = shelves
        return tabs
    }

    override fun getShelves(track: Track) = PagedData.Single {
        val (union, rec) = coroutineScope {
            val a = async {
                queries.getTrack(track.id).json.data.trackUnion
            }
            val b = queries.internalLinkRecommenderTrack(track.id).json.data.seoRecommendedTrack
            a.await() to b
        }
        val list = rec.items.mapNotNull { it.data?.toTrack() }.takeIf { it.isNotEmpty() }?.let {
            listOf(Shelf.Lists.Tracks("More like this", it))
        } ?: emptyList()
        val first = union.firstArtist?.items?.firstOrNull()?.toShelves(queries) ?: emptyList()
        val other = union.otherArtists?.items.orEmpty().map { it.toShelves(queries) }.flatten()
        list + first + other
    }

    override suspend fun getStreamableMedia(streamable: Streamable): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Source -> {
                api.token ?: throw ClientException.LoginRequired()
                val accessToken = api.auth.getToken()
                val url = queries.storageResolve(streamable.id).json.cdnUrl.first()
                val time = "time=${System.currentTimeMillis()}"
                val decryption = Streamable.Decryption.Widevine(
                    "https://spclient.wg.spotify.com/widevine-license/v1/audio/license?$time"
                        .toRequest(mapOf("Authorization" to "Bearer $accessToken")),
                    true
                )
                Streamable.Source.Http(
                    request = "$url&$time".toRequest(),
                    decryption = decryption,
                ).toMedia()
            }

            Streamable.MediaType.Background ->
                Streamable.Media.Background(streamable.id.toRequest())

            else -> throw IllegalStateException("Unsupported Streamable : $streamable")
        }
    }

    override suspend fun loadTrack(track: Track): Track = coroutineScope {
        val hasPremium = hasPremium()
        val canvas = async { queries.canvas(track.id).json.toStreamable() }
        val id = Base62.decode(track.id.substringAfter("spotify:track:"))
        queries.metadata4Track(id).json.toTrack(
            hasPremium,
            canvas.await()
        )
    }

    private suspend fun createRadio(id: String): Radio {
        val radioId = queries.seedToPlaylist(id).json.mediaItems.first().uri
        return queries.fetchPlaylist(radioId).json.data.playlistV2.toRadio()!!
    }

    override fun loadTracks(radio: Radio) = loadPlaylistTracks(radio.id)
    override suspend fun radio(track: Track, context: EchoMediaItem?) = createRadio(track.id)
    override suspend fun radio(album: Album) = createRadio(album.id)
    override suspend fun radio(artist: Artist) = createRadio(artist.id)
    override suspend fun radio(user: User) = createRadio(user.id)
    override suspend fun radio(playlist: Playlist) = createRadio(playlist.id)

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return queries.fetchPlaylist(playlist.id).json.data.playlistV2.toPlaylist()!!
    }

    private fun loadPlaylistTracks(id: String) = paged { offset ->
        val content = queries.fetchPlaylistContent(id, offset).json.data.playlistV2.content!!
        val tracks = content.items!!.map { it.itemV2?.data?.toTrack()!! }
        val page = content.pagingInfo!!
        val next = page.offset!! + page.limit!!
        tracks to if (content.totalCount!! > next) next else null
    }

    override fun loadTracks(playlist: Playlist) = loadPlaylistTracks(playlist.id)

    override fun getShelves(playlist: Playlist): PagedData<Shelf> = PagedData.Single {
        val playlists =
            queries.seoRecommendedPlaylist(playlist.id).json.data.seoRecommendedPlaylist.items.mapNotNull {
                it.toMediaItem()
            }
        if (playlists.isEmpty()) return@Single emptyList()
        listOf(
            Shelf.Lists.Items(
                title = "More Playlists like this",
                list = playlists,
            )
        )
    }

    override suspend fun loadAlbum(album: Album): Album {
        val res = queries.getAlbum(album.id)
        return res.json.data.albumUnion.toAlbum()!!.copy(
            extras = mapOf("raw" to res.raw)
        )
    }


    override fun getShelves(album: Album): PagedData<Shelf> = PagedData.Single {
        val union = api.json.decode<GetAlbum>(album.extras["raw"]!!).data.albumUnion
        union.moreAlbumsByArtist?.items.orEmpty().map { it.toShelves(queries) }.flatten()
    }

    override fun loadTracks(album: Album): PagedData.Continuous<Track> {
        var next = 0L
        return paged { offset ->
            val content = queries.queryAlbumTracks(album.id, offset).json.data.albumUnion.tracksV2!!
            val tracks = content.items!!.map {
                it.track?.toTrack(album)!!
            }
            next += tracks.size
            tracks to if (content.totalCount!! > next) next else null
        }
    }

    override suspend fun getHomeTabs(): List<Tab> {
        if (api.token == null) return emptyList()
        val all = listOf(Tab("", "All"))
        return all + queries.homeFeedChips().json.data?.home?.homeChips?.toTabs()!!
    }

    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        val home = if (tab == null || tab.id == "") queries.home(null).json.data?.home!!
        else queries.homeSubfeed(tab.id).json.data?.home!!
        home.run {
            sectionContainer?.sections?.toShelves(
                queries,
                greeting?.transformedLabel ?: "What's on your mind?"
            )!!
        }
    }

    override suspend fun onShare(item: EchoMediaItem): String {
        val type = item.id.substringAfter("spotify:").substringBefore(":")
        val id = item.id.substringAfter("spotify:$type:")
        return "https://open.spotify.com/$type/$id"
    }

    override fun getLibraryFeed(tab: Tab?): PagedData<Shelf> {
        if (api.token == null) throw ClientException.LoginRequired()
        return when (tab?.id) {
            "All", null -> pagedLibrary(queries)
            "You" -> PagedData.Single {
                val top = queries.userTopContent().json.data.me.profile

                val id = getCurrentUser()!!.id.substringAfter("spotify:user:")
                val uris = queries.recentlyPlayed(id).json.playContexts.map { it.uri }
                listOfNotNull(
                    Shelf.Lists.Items(
                        "Top Artist",
                        top.topArtists?.items.orEmpty().mapNotNull {
                            it.toMediaItem()
                        }
                    ),
                    Shelf.Lists.Tracks(
                        "Top Tracks",
                        top.topTracks?.items.orEmpty().mapNotNull {
                            (it.data as Item.Track).toTrack()
                        }
                    )
                ) + queries.fetchEntitiesForRecentlyPlayed(uris).json.data.lookup.mapNotNull {
                    it.data?.toMediaItem()?.toShelf()
                }
            }

            else -> pagedLibrary(queries, tab.id)
        }
    }

    override suspend fun getLibraryTabs(): List<Tab> {
        if (api.token == null) return emptyList()
        val filters = queries.libraryV3(0).json.data?.me?.libraryV3?.availableFilters
            ?.mapNotNull { it.name }.orEmpty()
        return (listOf("All", "You") + filters).map { Tab(it, it) }
    }

    override suspend fun isSavedToLibrary(mediaItem: EchoMediaItem): Boolean {
        if (api.token == null) return false
        val isSaved = queries.areEntitiesInLibrary(mediaItem.id)
            .json.data?.lookup?.firstOrNull()?.data?.saved
        println("is saved : $isSaved : ${mediaItem.title} ")
        return isSaved ?: false
    }

    override suspend fun removeFromLibrary(mediaItem: EchoMediaItem) {
        if (api.token == null) throw ClientException.LoginRequired()
        queries.removeFromLibrary(mediaItem.id)
    }

    override suspend fun saveToLibrary(mediaItem: EchoMediaItem) {
        if (api.token == null) throw ClientException.LoginRequired()
        println(queries.addToLibrary(mediaItem.id))
    }

    override suspend fun likeTrack(track: Track, isLiked: Boolean) {
        if (isLiked) queries.addToLibrary(track.id)
        else queries.removeFromLibrary(track.id)
    }

    override suspend fun hideTrack(track: Track, isHidden: Boolean) {
        TODO("Find a way to hide tracks, cause it's not available on web.")
    }

    override suspend fun followArtist(artist: Artist): Boolean {
        if (api.token == null) throw ClientException.LoginRequired()
        return when (val type = artist.id.substringAfter(":").substringBefore(":")) {
            "artist" -> {
                queries.addToLibrary(artist.id)
                true
            }

            "user" -> {
                val id = artist.id.substringAfter("spotify:user:")
                queries.followUsers(id)
                true
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    override suspend fun unfollowArtist(artist: Artist): Boolean {
        if (api.token == null) throw ClientException.LoginRequired()
        return when (val type = artist.id.substringAfter(":").substringBefore(":")) {
            "artist" -> {
                queries.removeFromLibrary(artist.id)
                true
            }

            "user" -> {
                val id = artist.id.substringAfter("spotify:user:")
                queries.unfollowUsers(id)
                true
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    override fun getShelves(artist: Artist) = PagedData.Single {
        when (val type = artist.id.substringAfter(":").substringBefore(":")) {
            "artist" -> {
                val res = api.json.decode<ArtistOverview>(artist.extras["raw"]!!)
                res.data.artistUnion.toShelves(queries)
            }

            "user" -> {
                val res = api.json.decode<UserProfileView>(artist.extras["raw"]!!)
                val id = artist.id.substringAfter("spotify:user:")
                listOfNotNull(
                    res.toShelf(),
                    queries.profileFollowers(id).json.toShelf("Followers"),
                    queries.profileFollowing(id).json.toShelf("Following")
                )
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    override suspend fun loadArtist(small: Artist): Artist {
        when (val type = small.id.substringAfter(":").substringBefore(":")) {
            "artist" -> {
                val res = queries.queryArtistOverview(small.id)
                return res.json.data.artistUnion.toArtist()!!.copy(
                    extras = mapOf("raw" to res.raw)
                )
            }

            "user" -> {
                val id = small.id.substringAfter("spotify:user:")
                val profile = queries.profileWithPlaylists(id)
                return profile.json.toArtist()!!.copy(
                    extras = mapOf("raw" to profile.raw)
                )
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    override fun searchTrackLyrics(clientId: String, track: Track) = PagedData.Single {
        val id = track.id.substringAfter("spotify:track:")
        val image = track.cover as ImageHolder.UrlRequestImageHolder
        val lyrics = runCatching { queries.colorLyrics(id, image.request.url).json.lyrics }
            .getOrNull() ?: return@Single emptyList<Lyrics>()
        var last = Long.MAX_VALUE
        val list = lyrics.lines?.reversed()?.mapNotNull {
            val start = it.startTimeMs?.toLong()!!
            val item = Lyrics.Item(
                it.words ?: return@mapNotNull null,
                startTime = start,
                endTime = last,
            )
            last = start
            item
        }?.reversed() ?: return@Single emptyList<Lyrics>()
        listOf(
            Lyrics(
                id = track.id,
                title = track.title,
                subtitle = lyrics.providerDisplayName,
                lyrics = Lyrics.Timed(list)
            )
        )
    }

    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics
}
