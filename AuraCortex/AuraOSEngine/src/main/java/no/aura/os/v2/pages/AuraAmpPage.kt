package no.aura.os.v2.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import no.aura.os.v2.amp.*
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.hero.HeroTitleText
import no.aura.os.v2.shell.auraNavigationFocusable
import no.aura.os.v2.shell.rememberNavigationInteraction
import no.aura.os.v2.theme.*

private enum class AmpView(val label:String) { HOME("Now Playing"), DISCOVER("Discover"), ALBUMS("Albums"), ARTISTS("Artists"), CHARTS("Charts"), PLAYLISTS("Playlists"), SERVERS("Media Servers"), POPULAR("Popular"), TRENDING("Trending"), SEARCH("Search") }

/** Public catalogue and private local listening state. Credentials never enter render state. */
@Composable
fun AuraAmpPage(profileId:String, entry:FocusRequester, playback:AmpPlayback, onConnect:()->Unit,
    rotationPaused:Boolean=false, mediaServers: no.aura.os.v2.mediaserver.MediaServerService?=null, auraMusicEngine: no.aura.music.engine.AuraMusicEngine?=null, onImport:((String)->Unit)->Unit, onAuraConnect:(()->Unit)?=null) {
    val context=LocalContext.current
    val albumLikes=remember(profileId) { AmpAlbumLikes(context,profileId) }
    var likedAlbumItems by remember(profileId) {mutableStateOf(albumLikes.albums())}
    val likedAlbums=likedAlbumItems.map{it.id}.toSet()
    var discoveryTab by remember(profileId) {mutableIntStateOf(0)}
    val followStore=remember(profileId){AmpArtistFollows(context,profileId)}
    var followedArtists by remember(profileId){mutableStateOf(followStore.artists())}
    var detailArtist by remember(profileId){mutableStateOf<AmpArtist?>(null)}
    var addTrack by remember(profileId){mutableStateOf<AmpTrack?>(null)}
    var addAlbum by remember(profileId) {mutableStateOf<AmpAlbum?>(null)}
    var addBusy by remember(profileId) {mutableStateOf(false)}
    var addVersion by remember(profileId) {mutableIntStateOf(0)}
    val currentProfile=rememberUpdatedState(profileId)
    val catalog=remember { AmpCatalog() }
    val likedTracks=remember(playback,playback.favourites) { playback.favouriteTracks() }
    var connectNotice by remember(profileId) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var view by remember(profileId) { mutableStateOf(AmpView.HOME) }
    var home by remember(profileId) { mutableStateOf<AmpHome?>(null) }
    var homeBusy by remember(profileId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    var detailTitle by remember(profileId) { mutableStateOf<String?>(null) }
    var detailTracks by remember(profileId) { mutableStateOf(emptyList<AmpTrack>()) }
    var detailAlbums by remember(profileId) { mutableStateOf(emptyList<AmpAlbum>()) }
    var detailBusy by remember { mutableStateOf(false) }
    var detailVersion by remember { mutableIntStateOf(0) }
    var albumPlayVersion by remember { mutableIntStateOf(0) }
    var newest by remember { mutableStateOf(true) }
    var albumFilterTab by remember { mutableIntStateOf(0) }
    var artistFilterTab by remember { mutableIntStateOf(0) }
    val itunesCatalog = remember { no.aura.os.v2.music.iTunesCatalog() }
    var itunesSongs by remember(profileId) { mutableStateOf(emptyList<AmpTrack>()) }
    var itunesAlbums by remember(profileId) { mutableStateOf(emptyList<AmpAlbum>()) }
    var chartsBusy by remember { mutableStateOf(false) }
    var chartsHeroIndex by remember { mutableIntStateOf(0) }
    var serverAccounts by remember(profileId) { mutableStateOf<List<no.aura.os.v2.mediaserver.ServerAccount>>(emptyList()) }
    var serverMusic by remember(profileId) { mutableStateOf<List<no.aura.os.v2.mediaserver.MediaEntry>>(emptyList()) }
    var serverMusicLoading by remember { mutableStateOf(false) }
    var query by remember(profileId) { mutableStateOf("") }
    var searchTracks by remember(profileId) { mutableStateOf(emptyList<AmpTrack>()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var datedPlaylists by remember(profileId) { mutableStateOf(emptyList<AmpPlaylist>()) }
    var libraryTab by remember(profileId) { mutableIntStateOf(0) }
    var screensaver by remember(profileId) { mutableStateOf(false) }
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val reduceMotion=LocalReduceMotion.current
    val heroEntry=remember { FocusRequester() }
    val bodyEntry=remember {FocusRequester()}
    val detailEntry=remember { FocusRequester() }
    val playerEntry=remember { FocusRequester() }
    val searchEntry=remember { FocusRequester() }
    val queueEntry=remember { FocusRequester() }
    val pageLists=remember(profileId) { AmpView.entries.associateWith { LazyListState() } }
    val detailList=remember(profileId) { LazyListState() }
    val list=if(detailTitle!=null)detailList else pageLists.getValue(view)
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(profileId,refresh,view==AmpView.HOME) {
        if(view==AmpView.HOME) {homeBusy=false;return@LaunchedEffect}
        homeBusy=true
        try { home=catalog.home { initial -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { home=initial } } } catch(e:CancellationException) { throw e } catch(_:Exception) { error="Music catalogue is unavailable. Try Refresh." }
        finally { homeBusy=false }
    }
    LaunchedEffect(view,home?.playlists) {
        if(view==AmpView.PLAYLISTS) {
            val gate=Semaphore(4)
            datedPlaylists=home?.playlists.orEmpty().map { source -> async {
                gate.withPermit {try{catalog.playlistInfo(source.id)}catch(e:CancellationException){throw e}catch(_:Exception){source}}
            }}.awaitAll()
        }
    }
    LaunchedEffect(query,view) {
        if(view!=AmpView.SEARCH) return@LaunchedEffect
        searchTracks=emptyList(); searchBusy=query.trim().length>=2
        if(!searchBusy) return@LaunchedEffect
        delay(350)
        try { searchTracks=catalog.search(query) } catch(e:CancellationException) {throw e} catch(_:Exception) {error="Music search is unavailable."} finally {searchBusy=false}
    }
    LaunchedEffect(view,detailVersion) { if(detailTitle!=null){detailList.scrollToItem(0);withFrameNanos{};runCatching{detailEntry.requestFocus()}} }
    LaunchedEffect(view) {
        if(view==AmpView.CHARTS && itunesSongs.isEmpty()) {
            chartsBusy=true
            try {
                itunesSongs = itunesCatalog.topSongs(25)
                itunesAlbums = itunesCatalog.topAlbums(25)
            } catch(_:Exception){} finally { chartsBusy=false }
        }
        if(view==AmpView.SERVERS && mediaServers != null && serverMusic.isEmpty()) {
            serverMusicLoading = true
            val accs = mediaServers.accounts(profileId)
            serverAccounts = accs
            val allMusic = mutableListOf<no.aura.os.v2.mediaserver.MediaEntry>()
            for (acc in accs) {
                val libs = mediaServers.libraryPlayback.libraries(profileId, acc)
                if (libs is no.aura.os.v2.mediaserver.MediaResult.Ok) {
                    for (lib in libs.value.filter { it.type == no.aura.os.v2.mediaserver.LibraryType.MUSIC }) {
                        val items = mediaServers.libraryPlayback.items(profileId, acc, lib, 0, 50)
                        if (items is no.aura.os.v2.mediaserver.MediaResult.Ok) {
                            allMusic.addAll(items.value.items)
                        }
                    }
                }
            }
            serverMusic = allMusic
            serverMusicLoading = false
        }
    }
    LaunchedEffect(home?.hero,heroFocused,contentFocused,rotationPaused,reduceMotion,view,detailTitle,addAlbum) {
        while((home?.hero?.size ?: 0)>1 && !heroFocused && !contentFocused && !rotationPaused && addAlbum==null && !reduceMotion && (view==AmpView.DISCOVER || view==AmpView.CHARTS) && detailTitle==null) {
            delay(20000);heroIndex=(heroIndex+1)%(home?.hero?.size ?: 1);chartsHeroIndex=(chartsHeroIndex+1)%(home?.hero?.size ?: 1)
        }
    }
    val ssStore = remember { no.aura.os.v2.screensaver.PrefsScreensaverStore(context) }
    val ssSettings = remember(screensaver) { ssStore.load() }
    LaunchedEffect(playback.playing, screensaver, ssSettings.autoStartTimeout) {
        if(playback.playing && !screensaver && ssSettings.autoStartTimeout != no.aura.os.v2.screensaver.AutoStartTimeout.OFF) {
            val delayMs = ssSettings.autoStartTimeout.minutes * 60_000L
            delay(delayMs)
            if(playback.playing && !screensaver) {
                screensaver = true
            }
        }
    }
    fun clearDetail() { detailArtist=null;albumPlayVersion++;detailVersion++;detailTitle=null;detailTracks=emptyList();detailAlbums=emptyList();detailBusy=false;error=null }
    fun openAlbum(album:AmpAlbum) {
        clearDetail(); val version=detailVersion; detailTitle=album.title;detailBusy=true
        scope.launch { try { val data=catalog.album(album.id); if(version==detailVersion) detailTracks=data.second }
            catch(e:CancellationException){throw e}catch(_:Exception){if(version==detailVersion) error="This album could not be loaded."}
            finally {if(version==detailVersion)detailBusy=false} }
    }
    fun playAlbum(album:AmpAlbum) {
        error=null
        val intent=playback.selectionVersion
        val request=++albumPlayVersion
        scope.launch {try {val tracks=catalog.album(album.id).second
            if(request==albumPlayVersion && playback.selectionVersion==intent){if(tracks.isEmpty())error="No songs are available for this album." else playback.play(tracks.first(),tracks)}
        }catch(e:CancellationException){throw e}catch(_:Exception){if(request==albumPlayVersion && playback.selectionVersion==intent)error="This album could not be loaded."}}
    }
    fun openArtist(artist:AmpArtist) {
        clearDetail();detailArtist=artist;val version=detailVersion; detailTitle=artist.name;detailBusy=true
        scope.launch { try { val albums=catalog.artistAlbums(artist.id); val tracks=catalog.artistTracks(artist.id)
                if(version==detailVersion){detailAlbums=albums;detailTracks=tracks} }
            catch(e:CancellationException){throw e}catch(_:Exception){if(version==detailVersion)error="This artist could not be loaded."}
            finally{if(version==detailVersion)detailBusy=false} }
    }
    fun openPlaylist(playlist:AmpPlaylist) {
        clearDetail();val version=detailVersion;detailTitle=playlist.title;detailBusy=true
        scope.launch {try { val data=catalog.playlist(playlist.id);if(version==detailVersion)detailTracks=data.second }
            catch(e:CancellationException){throw e}catch(_:Exception){if(version==detailVersion)error="This playlist could not be loaded."}
            finally{if(version==detailVersion)detailBusy=false} }
    }
    fun returnToLibrary() {
        clearDetail()
        scope.launch {withFrameNanos{};runCatching{if(view==AmpView.HOME)queueEntry.requestFocus() else entry.requestFocus()}}
    }
    fun LazyListScope.songRows(tracks:List<AmpTrack>,owner:AmpPlayback,player:FocusRequester,queue:Boolean=false,replaceQueue:Boolean=true) {
        trackRows(tracks,owner,player,queue,replaceQueue,entry=if(view==AmpView.SEARCH || detailTitle!=null && detailAlbums.isEmpty())bodyEntry else null,onAdd={track->addVersion++;error=null;addAlbum=null;addTrack=track})
    }
    BackHandler(detailTitle!=null || view!=AmpView.HOME) { if(detailTitle!=null)returnToLibrary() else view=AmpView.HOME }
    Column(Modifier.fillMaxSize().background(AuraColors.Background).padding(start=168.dp,top=96.dp,end=42.dp,bottom=24.dp)) {
        AmpWordmark()
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().height(64.dp).onPreviewKeyEvent {event->
                if(event.type==KeyEventType.KeyDown && event.key==Key.DirectionDown) {
                    scope.launch {
                        if(view!=AmpView.HOME)list.scrollToItem(0)
                        withFrameNanos{}
                        val destination=AmpFocusPolicy.down(view==AmpView.HOME,detailTitle!=null,
                            detailTracks.isNotEmpty() || detailAlbums.isNotEmpty(),view==AmpView.DISCOVER && !home?.hero.isNullOrEmpty(),
                            when(view){AmpView.ALBUMS->!home?.albums.isNullOrEmpty();AmpView.ARTISTS->!home?.artists.isNullOrEmpty();AmpView.SEARCH->searchTracks.isNotEmpty();else->false})
                        val target=when(destination){AmpFocusDestination.PLAYER->playerEntry;AmpFocusDestination.HERO->heroEntry;AmpFocusDestination.BODY->bodyEntry;AmpFocusDestination.DETAILS->detailEntry}
                        runCatching{target.requestFocus()}
                    }
                    true
                } else false
            },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(30.dp)) {
                listOf(AmpView.HOME,AmpView.DISCOVER,AmpView.ALBUMS,AmpView.ARTISTS,AmpView.CHARTS,AmpView.PLAYLISTS,AmpView.SERVERS).forEachIndexed { index,item ->
                    MusicLine(item.label,view==item,Modifier.then(if(index==0)Modifier.focusRequester(entry) else Modifier)) {
                        clearDetail();query="";view=item
                    }
                }
                Box(Modifier.weight(1f).height(1.dp).background(AuraColors.Border))
                BasicTextField(query,{query=it.take(160);clearDetail();view=AmpView.SEARCH},singleLine=true,
                    textStyle=TextStyle(color=Color.White,fontSize=26.sp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                    keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),modifier=Modifier.width(190.dp).focusRequester(searchEntry).onFocusChanged{searchFocused=it.isFocused}.padding(10.dp),
                    decorationBox={inner->Column {Box{if(query.isBlank())Text("Search",color=Color.White,fontSize=26.sp);inner()};Box(Modifier.fillMaxWidth().padding(top=6.dp).height(if(searchFocused)3.dp else 1.dp).background(if(searchFocused || view==AmpView.SEARCH)AuraColors.Focus else AuraColors.Border))}})
            }
            if(view!=AmpView.HOME || detailTitle!=null) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(if(detailTitle!=null)detailTitle!! else if(view==AmpView.HOME)"AuraAmp recommended" else view.label,color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.weight(1f))
                if(view!=AmpView.HOME || detailTitle!=null) MusicAction("Back",Modifier.focusRequester(detailEntry),onClick={if(detailTitle!=null)returnToLibrary()else view=AmpView.HOME})
                detailArtist?.let{artist->MusicAction(if(followedArtists.any{it.id==artist.id})"Unfollow artist" else "Follow artist"){runCatching{followedArtists=followStore.toggle(artist)}.onFailure{error="Could not save followed artist."}}}
                MusicAction("Refresh",onClick={clearDetail();refresh++})
            }
            error?.let { Text(it,color=AuraColors.Focus,fontSize=16.sp) }
            if(homeBusy && home==null) Text("Loading music…",color=AuraColors.TextSecondary,fontSize=18.sp)
            val data=home
            if(view!=AmpView.HOME && data!=null && data.errors.isNotEmpty()) Text(data.errors.joinToString(" · "),color=AuraColors.TextSecondary,fontSize=13.sp,maxLines=2)
            if(view==AmpView.HOME) {
                            AmpNowPlaying(
                                title=playback.selected?.title ?: "Your music, ready to play",
                                artist=playback.selected?.artist ?: "Choose a playlist or liked song below",
                                position=playback.positionMs,duration=playback.durationMs,playing=playback.playing,
                                shuffle=playback.shuffle,repeat=playback.repeat,hasTrack=playback.selected!=null,
                                status=if(playback.loading)"Connecting to your music…" else playback.error,
                                entry=playerEntry,libraryEntry=queueEntry,onToggle=playback::toggle,
                                onPrevious=playback::previous,onNext=playback::next,onShuffle=playback::toggleShuffle,
                                onRepeat=playback::toggleRepeat,onSeek=playback::seek,onScreensaver={screensaver=true},
                                artwork={mod->MusicArt(playback.selected?.cover,mod)}
                            )
                        Row(Modifier.fillMaxWidth().height(76.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                            MusicLine("Playlists",libraryTab==0,Modifier.focusRequester(queueEntry)){clearDetail();libraryTab=0}
                            MusicLine("Liked",libraryTab==1){clearDetail();libraryTab=1}
                            MusicLine("Queue (${playback.queue.size})",libraryTab==2){clearDetail();libraryTab=2}
                            MusicLine("Following",libraryTab==3){clearDetail();libraryTab=3}
                            Box(Modifier.weight(1f).height(1.dp).background(AuraColors.Border))
                            Row(Modifier.auraFocusable(RoundedCornerShape(10.dp),scale=1f){onAuraConnect?.invoke() ?: run {connectNotice=true}}.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                Icon(Icons.Rounded.Link,null,tint=Color.White,modifier=Modifier.size(36.dp))
                                Column {Text("AuraConnect",color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Sync your music",color=AuraColors.TextSecondary,fontSize=18.sp)}
                            }
                        }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().onFocusChanged{contentFocused=it.hasFocus},state=list,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=32.dp)) {
                if(detailTitle!=null) {
                    item { MusicLine(if(newest)"Newest first ↓" else "Oldest first ↑",false){newest=!newest} }
                    if(detailBusy)item {Text("Loading…",color=Color.White,fontSize=20.sp)}
                    if(detailAlbums.isNotEmpty()) {
                        item {MusicHeading("Albums")}
                        albumRows(AmpSort.albums(detailAlbums,newest),::openAlbum,bodyEntry)
                    }
                    item {MusicHeading("Songs · ${detailTracks.size} loaded")}
                    songRows(detailTracks,playback,playerEntry)
                    if(!detailBusy && detailTracks.isEmpty() && detailAlbums.isEmpty())item {EmptyMusic("No songs or albums available.")}
                } else when(view) {
                    AmpView.DISCOVER -> {
                        val heroes=data?.hero.orEmpty()
                        if(heroes.isNotEmpty())item {
                            val index=heroIndex.coerceIn(0,heroes.lastIndex)
                            val hero=heroes[index]
                            AmpDiscoverHero(heroes,index,hero.id in likedAlbums,heroEntry,{heroFocused=it},
                                onPrevious={heroIndex=Math.floorMod(index-1,heroes.size)},onNext={heroIndex=(index+1)%heroes.size},
                                onPlay={playAlbum(hero)},onLike={runCatching{likedAlbumItems=albumLikes.toggle(hero)}.onFailure{error="Album like could not be saved."}},
                                onAdd={addVersion++;error=null;addTrack=null;addAlbum=hero},artwork={url,mod->MusicArt(url,mod,Alignment.TopCenter)})
                        }
                        item {MusicHeading("Popular artists")}
                        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                            val artists=data?.artists.orEmpty().take(6)
                            artists.forEach {artist->
                                Column(Modifier.weight(1f).auraFocusable(RoundedCornerShape(16.dp)){openArtist(artist)}.padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    MusicArt(artist.picture,Modifier.size(148.dp).clip(CircleShape))
                                    Text(artist.name,color=Color.White,fontSize=23.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp))
                                }
                            }
                            repeat(6-artists.size){Spacer(Modifier.weight(1f))}
                        }}
                        item {Row(horizontalArrangement=Arrangement.spacedBy(26.dp)) {
                            listOf("Recently released","Trending","Recommended").forEachIndexed {index,label->MusicLine(label,discoveryTab==index){discoveryTab=index}}
                        }}
                        when(discoveryTab) {
                            0 -> {
                                item {Text(data?.releaseLabel ?: "Release selections",color=AuraColors.TextSecondary,fontSize=16.sp)}
                                albumRows(data?.releases.orEmpty(),::openAlbum)
                            }
                            1 -> {
                                item {Text("Based on Deezer charts",color=AuraColors.TextSecondary,fontSize=16.sp)}
                                if(!homeBusy && data?.tracks.isNullOrEmpty())item{EmptyMusic("Trending music is unavailable. Try Refresh.")}
                                songRows(data?.tracks.orEmpty(),playback,playerEntry)
                            }
                            else -> {
                                item {Text("AuraAmp recommended · editorial selection",color=AuraColors.TextSecondary,fontSize=16.sp)}
                                albumRows(data?.hero.orEmpty(),::openAlbum)
                            }
                        }
                    }
                    AmpView.SERVERS -> {
                        item { MusicHeading("Home Servers (Plex / Emby / Jellyfin)") }
                        if (mediaServers == null) item { EmptyMusic("Media servers are not connected in this build.") }
                        else {
                            if (serverMusicLoading) item { EmptyMusic("Loading music from home servers...") }
                            else if (serverAccounts.isEmpty()) item { EmptyMusic("No home servers connected. Go to Settings > Connections to add Plex, Jellyfin, or Emby.") }
                            else if (serverMusic.isEmpty()) item { EmptyMusic("No music found on your connected home servers.") }
                            else {
                                // For now, we just map MediaEntry to AmpTrack so it can be displayed.
                                // Playback is not yet integrated for these tracks.
                                val tracks = serverMusic.map { entry ->
                                    AmpTrack("server|${entry.id}", entry.title, entry.artist ?: "Unknown Artist", (entry.durationMs ?: 0L).toInt() / 1000, entry.imagePath)
                                }
                                songRows(tracks, playback, playerEntry)
                            }
                        }
                    }
                    AmpView.HOME -> {
                        when(libraryTab) {
                            0 -> {
                                if(playback.playlists.isEmpty())item {EmptyMusic("Your playlists will appear here. Connect your music or import a public playlist.")}
                                playlistRows(playback.playlists){playlist->clearDetail();detailTitle=playlist.title;detailTracks=playback.playlistTracks(playlist.id)}
                            }
                            1 -> {
                                val liked=likedTracks
                                if(likedAlbumItems.isNotEmpty()){item{MusicHeading("Liked albums")};albumRows(likedAlbumItems,::openAlbum);item{MusicHeading("Liked songs")}}
                                if(liked.isEmpty() && likedAlbumItems.isEmpty())item{EmptyMusic("Like songs to keep your favourites here.")}
                                songRows(liked,playback,playerEntry)
                            }
                            3 -> {
                                if(followedArtists.isEmpty())item{EmptyMusic("Follow artists from their artist page to find them here.")}
                                followedArtists.chunked(5).forEach{row->item{Row(horizontalArrangement=Arrangement.spacedBy(24.dp)){
                                    row.forEach{artist->Column(Modifier.width(250.dp).auraFocusable(RoundedCornerShape(14.dp)){openArtist(artist)},horizontalAlignment=Alignment.CenterHorizontally){MusicArt(artist.picture,Modifier.size(160.dp).clip(CircleShape));Text(artist.name,color=Color.White,fontSize=23.sp,maxLines=1)}}
                                }}}
                            }
                            else -> {
                                if(playback.queue.isEmpty())item{EmptyMusic("Your queue is empty. Choose music from your playlists.")}
                                songRows(playback.queue,playback,playerEntry,queue=true)
                            }
                        }
                    }
                    AmpView.ALBUMS -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("Latest", "Trending", "Popular", "Aura Recommended").forEachIndexed { i, label ->
                                    MusicLine(label, albumFilterTab == i) { albumFilterTab = i }
                                }
                                Spacer(Modifier.weight(1f))
                                MusicLine(if (newest) "Newest first ↓" else "Oldest first ↑", false) { newest = !newest }
                            }
                        }
                        val albumList = when (albumFilterTab) {
                            0 -> AmpSort.albums(data?.albums.orEmpty(), newest)
                            1 -> data?.releases.orEmpty()
                            2 -> data?.albums.orEmpty()
                            else -> data?.hero.orEmpty()
                        }
                        albumRows(albumList, ::openAlbum, bodyEntry)
                    }
                    AmpView.ARTISTS -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("Latest", "Trending", "Popular", "Aura Recommended").forEachIndexed { i, label ->
                                    MusicLine(label, artistFilterTab == i) { artistFilterTab = i }
                                }
                            }
                        }
                        val artistList = data?.artists.orEmpty()
                        artistList.chunked(4).forEach { row ->
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    row.forEach { artist ->
                                        val isFollowed = followedArtists.any { it.id == artist.id }
                                        Column(
                                            Modifier.weight(1f).then(if (artist.id == artistList.firstOrNull()?.id) Modifier.focusRequester(bodyEntry) else Modifier)
                                                .auraFocusable(RoundedCornerShape(14.dp)) { openArtist(artist) }.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            MusicArt(artist.picture, Modifier.size(160.dp).clip(CircleShape))
                                            Spacer(Modifier.height(8.dp))
                                            Text(artist.name, color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                MusicAction(if (isFollowed) "✓ Following" else "+ Follow") {
                                                    followedArtists = followStore.toggle(artist)
                                                }
                                            }
                                        }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    AmpView.CHARTS -> {
                        val heroes = data?.hero.orEmpty()
                        if (heroes.isNotEmpty()) {
                            item {
                                val cIndex = chartsHeroIndex.coerceIn(0, heroes.lastIndex)
                                val hero = heroes[cIndex]
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().height(440.dp)) {
                                        MusicArt(hero.heroImage ?: hero.cover, Modifier.fillMaxSize(), Alignment.TopCenter)
                                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha = .9f), .58f to AuraColors.Background.copy(alpha = .25f), 1f to Color.Transparent)))
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to AuraColors.Background)))
                                        Column(Modifier.padding(start = 42.dp, top = 40.dp, bottom = 45.dp).width(850.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.width(4.dp).height(20.dp).background(AuraColors.BrandOrange))
                                                Spacer(Modifier.width(10.dp))
                                                Text("LATEST ON AURA", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            }
                                            HeroTitleText(hero.title, width = 690.dp, height = 110.dp, maxFont = 72, minFont = 30)
                                            Text(hero.artist, color = Color.White, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                MusicAction("▶  Play album") { playAlbum(hero) }
                                                MusicAction(if (hero.id in likedAlbums) "♥ Liked" else "♡ Like") {
                                                    runCatching { likedAlbumItems = albumLikes.toggle(hero) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { MusicHeading("Apple iTunes Top Charts") }
                        if (chartsBusy) item { Text("Loading iTunes charts...", color = AuraColors.TextSecondary, fontSize = 16.sp) }
                        if (itunesSongs.isNotEmpty()) {
                            item { Text("Top Songs · Apple iTunes", color = AuraColors.TextSecondary, fontSize = 16.sp) }
                            songRows(itunesSongs, playback, playerEntry)
                        }
                        if (itunesAlbums.isNotEmpty()) {
                            item { Text("Top Albums · Apple iTunes", color = AuraColors.TextSecondary, fontSize = 16.sp) }
                            albumRows(itunesAlbums, ::openAlbum)
                        }

                        item { MusicHeading("Deezer Global Charts") }
                        item { Text("Top Albums · Deezer Chart", color = AuraColors.TextSecondary, fontSize = 16.sp) }
                        albumRows(data?.albums.orEmpty(), ::openAlbum)
                        item { Text("Top Tracks · Deezer Chart", color = AuraColors.TextSecondary, fontSize = 16.sp) }
                        songRows(data?.tracks.orEmpty(), playback, playerEntry)
                    }
                    AmpView.PLAYLISTS -> {
                        item {Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                            MusicLine(if(newest)"Newest first ↓" else "Oldest first ↑",false){newest=!newest}
                            MusicAction("Import public playlist"){onImport {raw->
                                val id=runCatching {if(raw.trim().matches(Regex("[0-9]{1,20}")))raw.trim() else java.net.URI(raw.trim()).takeIf{it.scheme=="https" && it.host in setOf("www.deezer.com","deezer.com") && it.userInfo==null}?.path?.split('/')?.let{parts->parts.indexOf("playlist").takeIf{it>=0}?.let{parts.getOrNull(it+1)}}}.getOrNull()
                                if(id==null || !id.matches(Regex("[0-9]{1,20}")))error="Enter a Deezer public playlist link or numeric ID."
                                else scope.launch {try {val imported=catalog.playlist(id);playback.importPlaylist(imported.first,imported.second)}catch(e:CancellationException){throw e}catch(_:Exception){error="Public playlist import failed."}}
                            }}
                        }}
                        item {MusicHeading("Your imported playlists")}
                        val own=AmpSort.playlists(playback.playlists,newest)
                        if(own.isEmpty())item {EmptyMusic("Import your public Deezer playlist using its link. Private account sync is not connected.")}
                        playlistRows(own){playlist->clearDetail();detailTitle=playlist.title;detailTracks=playback.playlistTracks(playlist.id)}
                        item {MusicHeading("Popular playlists · Deezer charts")}
                        playlistRows(AmpSort.playlists(datedPlaylists.ifEmpty{data?.playlists.orEmpty()},newest),::openPlaylist)
                    }
                    AmpView.POPULAR -> {
                        item {Text("Popular songs · Deezer chart ranking",color=AuraColors.TextSecondary,fontSize=16.sp)}
                        songRows(data?.tracks.orEmpty(),playback,playerEntry)
                        item {MusicHeading("Popular albums")};albumRows(data?.albums.orEmpty(),::openAlbum)
                    }
                    AmpView.TRENDING -> {
                        item {Text("Trending · Deezer charts. Listening-growth data is not supplied.",color=AuraColors.TextSecondary,fontSize=16.sp)}
                        item {AlbumStrip(data?.hero.orEmpty(),::openAlbum)}
                        songRows(data?.tracks.orEmpty(),playback,playerEntry)
                    }
                    AmpView.SEARCH -> {
                        item {Text(if(searchBusy)"Searching…" else if(query.length<2)"Type at least two characters" else "${searchTracks.size} songs · Deezer",color=AuraColors.TextSecondary,fontSize=18.sp)}
                        songRows(searchTracks,playback,playerEntry)
                    }
                }
            }
        }
    }
    if(addAlbum!=null || addTrack!=null) no.aura.os.v2.overlay.CanvasDialog({addVersion++;addAlbum=null;addTrack=null;addBusy=false;scope.launch{withFrameNanos{};runCatching{entry.requestFocus()}}}) {
        val addScope=rememberCoroutineScope()
        val first=remember {FocusRequester()}
        val album=addAlbum
        val track=addTrack
        val addTitle=album?.title ?: track!!.title
        LaunchedEffect(addBusy){withFrameNanos{};first.requestFocus()}
        fun appendTo(target:AmpPlaylist?) {
            if(addBusy)return
            addBusy=true
            val request=++addVersion
            addScope.launch {
                try {
                    val songs=track?.let{listOf(it)} ?: catalog.album(album!!.id).second
                    require(songs.isNotEmpty())
                    val playlist=target ?: AmpPlaylist("local:"+java.util.UUID.randomUUID(),addTitle,album?.cover ?: track?.cover)
                    val merged=AmpPlaylistAppend.merge(target?.let{playback.playlistTracks(it.id)}.orEmpty(),songs)
                    if(request!=addVersion || (album!=null && addAlbum?.id!=album.id) || (track!=null && addTrack?.id!=track.id) || currentProfile.value!=profileId)return@launch
                    playback.importPlaylist(playlist.copy(trackCount=merged.size),merged)
                    addAlbum=null;addTrack=null
                    scope.launch{withFrameNanos{};runCatching{entry.requestFocus()}}
                } catch(e:CancellationException){throw e} catch(_:Exception){error="Could not add the music. Check the connection and playlist size."}
                finally {if(request==addVersion)addBusy=false}
            }
        }
        Column(Modifier.width(720.dp).heightIn(max=700.dp).background(AuraColors.Surface,RoundedCornerShape(20.dp)).padding(28.dp)) {
            Text("Add to playlist",color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold)
            Text(addTitle,color=AuraColors.TextSecondary,fontSize=22.sp,maxLines=1)
            Text("Saved to this AuraOS profile",color=AuraColors.TextSecondary,fontSize=16.sp)
            error?.let{Text(it,color=AuraColors.TextSecondary,fontSize=18.sp)}
            if(addBusy)Text("Adding to playlist…",color=Color.White,fontSize=22.sp)
            if(!addBusy)LazyColumn {
                item {MusicAction("New playlist: $addTitle",Modifier.focusRequester(first)){appendTo(null)}}
                items(playback.playlists,key={it.id}) {playlist->MusicAction(playlist.title){appendTo(playlist)}}
            }
            MusicAction("Cancel",if(addBusy)Modifier.focusRequester(first)else Modifier){addVersion++;addAlbum=null;addTrack=null;addBusy=false;scope.launch{withFrameNanos{};runCatching{entry.requestFocus()}}}
        }
    }
    if(connectNotice) no.aura.os.v2.overlay.CanvasDialog({connectNotice=false}) {
        val close=remember {FocusRequester()}
        LaunchedEffect(Unit){withFrameNanos{};close.requestFocus()}
        Column(Modifier.width(600.dp).background(AuraColors.Surface,RoundedCornerShape(18.dp)).padding(28.dp)) {
            Text("AuraConnect",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Bold)
            Text("Phone-to-TV library sync is not connected in this build. Existing music source settings are available under Connections.",color=AuraColors.TextSecondary,fontSize=20.sp)
            MusicAction("Music source settings",onClick={connectNotice=false;onConnect()})
            MusicAction("Import public playlist"){connectNotice=false;clearDetail();view=AmpView.PLAYLISTS}
            MusicAction("Close",Modifier.focusRequester(close)){connectNotice=false;runCatching{queueEntry.requestFocus()}}
        }
    }
    if(screensaver) no.aura.os.v2.screensaver.ScreensaverHost(
        playback = playback,
        onClose = { screensaver = false; scope.launch { withFrameNanos { }; runCatching { playerEntry.requestFocus() } } }
    )
}



@Composable private fun MusicHeading(text:String) { Text(text,color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
@Composable private fun EmptyMusic(text:String) { Text(text,color=AuraColors.TextSecondary,fontSize=18.sp,modifier=Modifier.padding(vertical=12.dp)) }
@Composable private fun MusicArt(url:String?,modifier:Modifier,alignment:Alignment=Alignment.Center) { Box(modifier.background(AuraColors.Surface),contentAlignment=Alignment.Center){if(url==null)Icon(Icons.Rounded.MusicNote,null,tint=AuraColors.TextMuted,modifier=Modifier.size(100.dp))else ArtworkImage(Artwork(imageUrl=url),Modifier.fillMaxSize(),alignment=alignment)} }
@Composable private fun MusicLine(label:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val interaction=rememberNavigationInteraction()
    Text(label,color=Color.White,fontSize=26.sp,maxLines=1,modifier=modifier.auraNavigationFocusable(interaction,selected,onClick=onClick).padding(horizontal=10.dp,vertical=12.dp))
}
@Composable private fun MusicAction(label:String,modifier:Modifier=Modifier,description:String=label,onClick:()->Unit) {
    Text(label,color=Color.White,fontSize=18.sp,maxLines=1,modifier=modifier.semantics{contentDescription=description}.auraFocusable(RoundedCornerShape(10.dp),scale=1f,onClick=onClick).padding(horizontal=12.dp,vertical=10.dp))
}
@Composable private fun AlbumStrip(albums:List<AmpAlbum>,onOpen:(AmpAlbum)->Unit) {
    if(albums.isEmpty())EmptyMusic("No albums available.")
    LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)){items(albums,key={it.id}){album->AlbumTile(album,Modifier.width(275.dp),onOpen)}}
}
@Composable private fun AlbumTile(album:AmpAlbum,modifier:Modifier=Modifier,onOpen:(AmpAlbum)->Unit) {
    Column(modifier.auraFocusable(RoundedCornerShape(14.dp)){onOpen(album)}.clip(RoundedCornerShape(14.dp))) {
        MusicArt(album.cover,Modifier.fillMaxWidth().height(135.dp));Text(album.title,color=Color.White,fontSize=19.sp,maxLines=1)
        Text(listOfNotNull(album.artist.takeIf{it.isNotBlank()},album.releaseDate).joinToString(" · "),color=AuraColors.TextSecondary,fontSize=15.sp,maxLines=1)
    }
}
private fun LazyListScope.albumRows(albums:List<AmpAlbum>,onOpen:(AmpAlbum)->Unit,entry:FocusRequester?=null) {
    if(albums.isEmpty())item{EmptyMusic("No albums available.")}
    albums.chunked(4).forEach{row->item {Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){row.forEach{AlbumTile(it,Modifier.weight(1f).then(if(it.id==albums.firstOrNull()?.id && entry!=null)Modifier.focusRequester(entry)else Modifier),onOpen)};repeat(4-row.size){Spacer(Modifier.weight(1f))}}}}
}
private fun LazyListScope.playlistRows(playlists:List<AmpPlaylist>,onOpen:(AmpPlaylist)->Unit) {
    playlists.chunked(4).forEach{row->item {Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){row.forEach{p->
        Box(Modifier.weight(1f).height(205.dp).auraFocusable(RoundedCornerShape(18.dp)){onOpen(p)}.clip(RoundedCornerShape(18.dp)).border(1.dp,Color.White.copy(alpha=.14f),RoundedCornerShape(18.dp))) {
            MusicArt(p.picture,Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))))
            Text(p.title,color=Color.White,fontSize=27.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.align(Alignment.BottomStart).padding(20.dp))
        }
    };repeat(4-row.size){Spacer(Modifier.weight(1f))}}}}
}
private fun LazyListScope.trackRows(tracks:List<AmpTrack>,playback:AmpPlayback,player:FocusRequester,queue:Boolean=false,replaceQueue:Boolean=true,entry:FocusRequester?=null,onAdd:(AmpTrack)->Unit) {
    itemsIndexed(tracks,key={index,track -> "${track.id}:$index"}) {index,track->
        Row(Modifier.fillMaxWidth().height(58.dp).background(if(playback.selected?.id==track.id)AuraColors.Focus.copy(alpha=.12f)else Color.Transparent,RoundedCornerShape(10.dp)),verticalAlignment=Alignment.CenterVertically) {
            MusicArt(track.cover,Modifier.size(56.dp,48.dp).clip(RoundedCornerShape(6.dp)))
            MusicAction(track.title,Modifier.weight(1f).then(if(index==0 && entry!=null)Modifier.focusRequester(entry)else Modifier)){if(queue)playback.playQueued(index)else playback.play(track,if(replaceQueue)tracks else emptyList())}
            Text(track.artist,color=AuraColors.TextSecondary,fontSize=16.sp,maxLines=1,modifier=Modifier.width(180.dp))
            Text(ampDemoTime(track.duration),color=Color.White,fontSize=16.sp)
            MusicIcon(if(track.id in playback.favourites)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,"Favorite ${track.title}",selected=track.id in playback.favourites){playback.toggleFavourite(track)}
            MusicIcon(Icons.Rounded.PlaylistAdd,"Add ${track.title} to playlist"){onAdd(track)}
            MusicIcon(if(queue)Icons.Rounded.PlaylistRemove else Icons.Rounded.QueueMusic,if(queue)"Remove ${track.title} from queue" else "Queue ${track.title}",Modifier){if(queue){playback.removeQueuedAt(index);runCatching{player.requestFocus()}}else playback.enqueue(track)}
        }
    }
}
@Composable private fun ReleaseStrip(albums:List<AmpAlbum>,onPlay:(AmpAlbum)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        val featured=albums.take(4)
        featured.forEach {album->
            val shape=RoundedCornerShape(14.dp)
            Box(Modifier.weight(1f).height(144.dp).auraFocusable(shape){onPlay(album)}.clip(shape).border(1.dp,Color.White.copy(alpha=.14f),shape)) {
                MusicArt(album.cover,Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=.06f),Color.Transparent,Color.Black.copy(alpha=.86f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(.79f)) {
                    Text(album.title,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(album.artist,color=Color.White.copy(alpha=.8f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha=.45f)).border(1.dp,AuraColors.Focus.copy(alpha=.75f),CircleShape),contentAlignment=Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow,null,tint=Color.White,modifier=Modifier.size(22.dp))
                }
            }
        }
        repeat(4-featured.size){Spacer(Modifier.weight(1f))}
    }
}
@Composable private fun MusicPrimary(label:String,onClick:()->Unit) {
    Row(Modifier.auraFocusable(RoundedCornerShape(12.dp),scale=1f,onClick=onClick).clip(RoundedCornerShape(12.dp))
        .background(AuraColors.ButtonGradient).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.PlayArrow,null,tint=Color.White,modifier=Modifier.size(25.dp))
        Text(label,color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable private fun MusicIcon(icon:ImageVector,label:String,modifier:Modifier=Modifier,selected:Boolean=false,enabled:Boolean=true,onClick:()->Unit) {
    val shape=RoundedCornerShape(14.dp)
    Box(modifier.size(48.dp).semantics{contentDescription=label;role=Role.Button;if(!enabled)disabled()}
        .then(if(enabled)Modifier.auraFocusable(shape,scale=1f,onClick=onClick)else Modifier).clip(shape)
        .background(if(selected)AuraColors.Focus.copy(alpha=.14f)else AuraColors.ButtonInactive),contentAlignment=Alignment.Center) {
        Icon(icon,null,tint=if(enabled)AuraColors.ButtonIcon else AuraColors.TextMuted.copy(alpha=.5f),modifier=Modifier.size(28.dp))
    }
}
