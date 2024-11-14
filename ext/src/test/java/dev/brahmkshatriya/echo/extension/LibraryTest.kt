package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.spotify.Base62
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class LibraryTest {
    private val extension = SpotifyExtension()

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")
    private val token = System.getenv("SPOTIFY_TOKEN")!!
    private val user = User(token, "")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onExtensionSelected()
            extension.onSetLoginUser(user)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }

    @Test
    fun testCurrentUser() = testIn("Testing current user") {
        val user = extension.getCurrentUser()
        println(user)
        println(extension.queries.metadata4Track("9ddc535dfd344ebbb280514a0d7dc5c3"))
    }

    private fun Shelf.print() {
        when (this) {
            is Shelf.Category -> println(this)
            is Shelf.Item -> println(this)
            is Shelf.Lists<*> -> {
                println("${this.title} : ${this.list.size}")
                this.list.forEach { println(it) }
            }
        }
    }

    @Test
    fun testSearchEmpty() = testIn("Testing Empty Search") {
        val tab = extension.searchTabs(null).firstOrNull()
        val feed = extension.searchFeed(null, tab)
        val shelves = feed.loadAll()
        val shelf = shelves.first() as Shelf.Lists.Categories
        val load = shelf.list.first().items!!.loadFirst()
        load.forEach { it.print() }
    }

    private val searchQuery = "hero"

    @Test
    fun testSearch() = testIn("Testing Search") {
        val tabs = extension.searchTabs(searchQuery)
        tabs.forEach {
            println("Tab: $it")
            val feed = extension.searchFeed(searchQuery, it)
            val shelves = feed.loadFirst()
            shelves.forEach { it.print() }
        }
    }

    @Test
    fun getTrack() = testIn("Get Track") {
        val track = extension.loadTrack(Track("spotify:track:4NSu2c1qZgHhU67MNkd5Hd", ""))
        println(track)
        track.sources.forEach { streamable ->
            println(streamable)
            val media = extension.getStreamableMedia(streamable)
            println(media)
        }
    }

    @Test
    fun getLyrics() = testIn("Get Lyrics") {
        val lyrics = extension.queries.api.call(
            Request.Builder()
                .url("https://spclient.wg.spotify.com/color-lyrics/v2/track/71NTIlx3GOoJdDDChHcMx3/image/https%3A%2F%2Fi.scdn.co%2Fimage%2Fab67616d0000b273b1b4f333c30b954b3ccd4d81?format=json&vocalRemoval=false&market=from_token")
                .build()
        )
        println(lyrics)
    }

    @Test
    fun testGid() = testIn("GID Test") {
        val actual = "4eNGmBfayErwCHSKW3F0ek"
        val gid = Base62.decode(actual)
        println(gid)
        val id = Base62.encode(gid)
        println(id)
        Assert.assertEquals(actual, id)
    }

    @Test
    fun testPlaylist() = testIn("Playlist Test") {
        val playlist = extension.loadPlaylist(
            Playlist("spotify:playlist:6ZBHUwSlPqlvLsw0MKJtLU", "", false)
        )
        println(playlist)
        val tracks = extension.loadTracks(playlist).loadAll()
        println("Tracks: ${tracks.size}")
    }

    @Test
    fun testAlbum() = testIn("Album Test") {
        val album = extension.loadAlbum(
            Album("spotify:album:6a6wiQNPcQMV8K17HDKtrC", "")
        )
        println(album)
        val tracks = extension.loadTracks(album).loadAll()
        println("Tracks: ${tracks.size}")
    }
}