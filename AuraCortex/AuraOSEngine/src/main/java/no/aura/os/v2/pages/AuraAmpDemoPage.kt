package no.aura.os.v2.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.aura.os.v2.amp.*
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.LocalReduceMotion

@Stable class AuraAmpDemoSession {
    var state by mutableStateOf(AmpDemoState())
    var tab by mutableStateOf(AmpDemoTab.DISCOVER)
    var query by mutableStateOf("")
    var album by mutableIntStateOf(0)
    var module by mutableStateOf(AmpDemoModule.AMP)
    var trackTab by mutableStateOf(AmpDemoTrackTab.RECENTLY_PLAYED)
    var page by mutableIntStateOf(0)
    var libraryTab by mutableIntStateOf(0)
    var demoPlaylists by mutableStateOf(listOf("Evening favourites","Weekend mix","Quiet moments","My collection").associateWith {AmpDemoCatalog.tracks.take(3).map{it.id}})
    var albumLikes by mutableStateOf(emptySet<String>())
}

/** Same playback-first presentation, isolated profile-owned simulation. Never opens an audio source. */
@Composable fun AuraAmpDemoPage(entry:FocusRequester,modifier:Modifier=Modifier,onSearch:()->Unit={},
    onMessage:(String)->Unit={},session:AuraAmpDemoSession=remember{AuraAmpDemoSession()},
    externalTitle:String?=null,externalSubtitle:String?=null) {
    var state by session::state
    var page by session::page
    var libraryTab by session::libraryTab
    val scope=rememberCoroutineScope()
    var saver by remember(session) {mutableStateOf(false)}
    var playlist by remember(session) {mutableStateOf<String?>(null)}
    val hero=remember {FocusRequester()}
    var heroFocused by remember(session){mutableStateOf(false)}
    val reduceMotion=LocalReduceMotion.current
    var discoverTab by remember(session) {mutableIntStateOf(0)}
    var demoAddSong by remember(session){mutableStateOf<AmpDemoTrack?>(null)}
    val firstBrowse=remember {FocusRequester()}
    val demoArtistNames=listOf("Maya Sol","Jonas Vale","Lena Rivers","Kai Mercer","Elara Hart")
    var demoAdd by remember(session) {mutableStateOf<AmpAlbum?>(null)}
    val demoAlbums=remember {listOf(
        AmpAlbum("demo:0","AFTERGLOW","Jonas Vale",cover="feature"),
        AmpAlbum("demo:1","Velvet Horizon","Lena Rivers",cover="release1"),
        AmpAlbum("demo:2","Golden Tides","Kai Mercer",cover="release2"),
        AmpAlbum("demo:3","Nightfall Waltz","Elara Hart",cover="release3")
    )}
    val player=remember {FocusRequester()}
    val detailFocus=remember {FocusRequester()}
    val library=remember {FocusRequester()}
    val context=LocalContext.current
    val art by produceState<Map<String,ImageBitmap>>(emptyMap(),context) {value=runCatching{AmpDemoArtwork.load(context.applicationContext)}.getOrDefault(emptyMap())}
    val pages=remember(session) {(0..3).associateWith {LazyListState()}}
    val details=remember(session) {LazyListState()}
    val list=if(playlist!=null)details else pages.getValue(page)
    LaunchedEffect(playlist){if(playlist!=null){details.scrollToItem(0);withFrameNanos{};detailFocus.requestFocus()}}
    fun closePlaylist(){playlist=null;scope.launch{withFrameNanos{};runCatching{if(page==0)library.requestFocus()else entry.requestFocus()}}}
    LaunchedEffect(page,playlist,demoAdd,heroFocused,reduceMotion) {
        while(page==1 && playlist==null && demoAdd==null && !heroFocused && !reduceMotion){delay(8000);session.album=(session.album+1)%demoAlbums.size}
    }
    LaunchedEffect(state.playing) {while(state.playing){delay(1000);state=state.tick()}}
    BackHandler(page!=0 || playlist!=null){if(playlist!=null)closePlaylist()else page=0}
    @Composable fun cover(id:String,mod:Modifier) {
        Box(mod.background(AuraColors.Surface)) {art[id]?.let {Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}}
    }
    Column(modifier.fillMaxSize().background(AuraColors.Background).padding(start=168.dp,top=96.dp,end=42.dp,bottom=24.dp)) {
        AmpWordmark()
        Row(Modifier.fillMaxWidth().height(64.dp).onPreviewKeyEvent{event->
            if(event.type==KeyEventType.KeyDown && event.key==Key.DirectionDown){scope.launch{if(page!=0)list.scrollToItem(0);withFrameNanos{};runCatching{(if(playlist!=null)detailFocus else if(page==0)player else if(page==1)hero else firstBrowse).requestFocus()}};true}else false
        },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(30.dp)) {
            listOf("Now Playing","Discover","Albums","Artists").forEachIndexed {index,label->AmpLine(label,page==index,if(index==0)Modifier.focusRequester(entry)else Modifier){page=index;playlist=null}}
            Box(Modifier.weight(1f).height(1.dp).background(AuraColors.Border))
            AmpLine("Search"){onSearch()}
        }
        Text("DEMO · Simulated playback · No audio",color=AuraColors.TextSecondary,fontSize=14.sp)
        if(externalTitle!=null)Text("Music metadata only: $externalTitle${externalSubtitle?.let { " · $it" }.orEmpty()} · no audio source",color=AuraColors.TextSecondary,fontSize=14.sp,maxLines=1)
        if(page==0) {
                AmpNowPlaying(state.selected.title,state.selected.artist,state.seconds*1000L,state.selected.duration*1000L,state.playing,state.shuffle,state.repeat,true,null,player,library,
                    onToggle={state=state.copy(playing=!state.playing)},onPrevious={state=state.seek(0)},onNext={state=state.next()},
                    onShuffle={state=state.copy(shuffle=!state.shuffle)},onRepeat={state=state.copy(repeat=!state.repeat)},
                    onSeek={state=state.seek((it/1000).toInt())},onScreensaver={saver=true},artwork={cover(state.selected.art,it)})
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    listOf("Playlists","Liked","Queue (${state.queue.size})","Following").forEachIndexed {index,label->AmpLine(label,libraryTab==index,if(index==0)Modifier.focusRequester(library)else Modifier){playlist=null;libraryTab=index}}
                    Spacer(Modifier.weight(1f));AmpLine("AuraConnect"){onMessage("AuraConnect is not available in the simulated demo.")}
                }
        }
        LazyColumn(Modifier.weight(1f),state=list,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(page==0 && playlist==null) {
                if(libraryTab==0) item {Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    session.demoPlaylists.keys.take(4).forEachIndexed {i,label->
                        Box(Modifier.weight(1f).height(205.dp).auraFocusable(RoundedCornerShape(18.dp)){playlist=label}.clip(RoundedCornerShape(18.dp))) {
                            cover(listOf("bloom","golden","northern","night")[i],Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))))
                            Text(label,color=Color.White,fontSize=27.sp,modifier=Modifier.align(Alignment.BottomStart).padding(20.dp))
                        }
                    }
                }}
            } else item {Text(playlist ?: listOf("Now Playing","Discover","Albums","Artists")[page],color=Color.White,fontSize=28.sp)}
            if(playlist==null && page==1) item {
                val index=session.album.coerceIn(demoAlbums.indices)
                val album=demoAlbums[index]
                AmpDiscoverHero(demoAlbums,index,album.id in session.albumLikes,hero,{heroFocused=it},
                    onPrevious={session.album=Math.floorMod(index-1,demoAlbums.size)},onNext={session.album=(index+1)%demoAlbums.size},
                    onPlay={state=state.select(AmpDemoCatalog.tracks[index].id)},
                    onLike={session.albumLikes=if(album.id in session.albumLikes)session.albumLikes-album.id else session.albumLikes+album.id},
                    onAdd={demoAdd=album},artwork={id,mod->cover(id ?: "feature",mod)})
            }
            if(playlist==null && page==2) item {
                Text(if(page==1)"Recently released" else "Albums",color=Color.White,fontSize=24.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    listOf("Afterglow","Velvet Horizon","Golden Tides","Nightfall Waltz").forEachIndexed {i,label->
                        Column(Modifier.weight(1f).then(if(i==0)Modifier.focusRequester(firstBrowse)else Modifier).auraFocusable(RoundedCornerShape(14.dp)){playlist=label}) {
                            cover("release$i",Modifier.fillMaxWidth().height(if(page==2)240.dp else 160.dp).clip(RoundedCornerShape(14.dp)))
                            Text(label,color=Color.White,fontSize=27.sp,modifier=Modifier.padding(8.dp))
                        }
                    }
                }
            }
            if(playlist==null && page in listOf(1,3)) item {
                Text(if(page==1)"Popular artists" else "Artists",color=Color.White,fontSize=24.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(26.dp)) {
                    demoArtistNames.forEachIndexed {i,name->
                        Column(Modifier.weight(1f).then(if(i==0)Modifier.focusRequester(firstBrowse)else Modifier).auraFocusable(RoundedCornerShape(14.dp)){playlist=name},horizontalAlignment=Alignment.CenterHorizontally) {
                            cover("artist$i",Modifier.size(160.dp).clip(androidx.compose.foundation.shape.CircleShape))
                            Text(name,color=Color.White,fontSize=21.sp,modifier=Modifier.padding(8.dp))
                        }
                    }
                }
            }
            if(playlist==null && page==1) {
                item {Row(horizontalArrangement=Arrangement.spacedBy(26.dp)) {
                    listOf("Recently released","Trending","Recommended").forEachIndexed {index,label->AmpLine(label,discoverTab==index){discoverTab=index}}
                }}
                if(discoverTab!=1)item {Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    val albums=if(discoverTab==2)demoAlbums.reversed() else demoAlbums
                    albums.forEach {album->Column(Modifier.weight(1f).auraFocusable(RoundedCornerShape(14.dp)){playlist=album.title}) {
                        cover(album.cover ?: "feature",Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)))
                        Text(album.title,color=Color.White,fontSize=22.sp,maxLines=1)
                    }}
                }}
            }
            if(page==0 && playlist==null && libraryTab==1 && session.albumLikes.isNotEmpty())item {
                Text("Liked albums",color=Color.White,fontSize=24.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    demoAlbums.filter{it.id in session.albumLikes}.forEach {album->Column(Modifier.width(300.dp).auraFocusable(RoundedCornerShape(14.dp)){playlist=album.title}) {
                        cover(album.cover ?: "feature",Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)))
                        Text(album.title,color=Color.White,fontSize=22.sp,maxLines=1)
                    }}
                }
            }
            if(page==0 && playlist==null && libraryTab==3)item {
                Text("Following",color=Color.White,fontSize=26.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    demoArtistNames.forEachIndexed{i,name->if(name in state.followed)Column(Modifier.width(220.dp).auraFocusable(RoundedCornerShape(14.dp)){playlist=name},horizontalAlignment=Alignment.CenterHorizontally){cover("artist$i",Modifier.size(150.dp).clip(androidx.compose.foundation.shape.CircleShape));Text(name,color=Color.White,fontSize=22.sp)}}
                }
                if(state.followed.isEmpty())Text("Follow artists from their artist page.",color=AuraColors.TextSecondary,fontSize=20.sp)
            }
            if(playlist in demoArtistNames)item{AmpLine(if(playlist in state.followed)"Unfollow artist" else "Follow artist"){playlist?.let{state=state.toggleFollow(it)}}}
            val tracks=when {
                playlist!=null -> session.demoPlaylists[playlist]?.mapNotNull(AmpDemoCatalog::track) ?: AmpDemoCatalog.tracks
                page==1 && discoverTab==1 -> AmpDemoCatalog.tracks
                page!=0 -> emptyList()
                libraryTab==1 -> AmpDemoCatalog.tracks.filter{it.id in state.favourites}
                libraryTab==2 -> state.queue.mapNotNull(AmpDemoCatalog::track)
                else -> emptyList()
            }
            if(page==0 && libraryTab==1 && tracks.isEmpty() && session.albumLikes.isEmpty())item{Text("Liked songs will appear here.",color=AuraColors.TextSecondary,fontSize=20.sp)}
            if(page==0 && libraryTab==2 && tracks.isEmpty())item{Text("Your queue is empty.",color=AuraColors.TextSecondary,fontSize=20.sp)}
            if(playlist!=null)item{AmpLine("Back to list",modifier=Modifier.focusRequester(detailFocus)){closePlaylist()}}
            items(tracks,key={it.id}) {track->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                cover(track.art,Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)))
                AmpLine("${track.title}  ·  ${track.artist}",modifier=Modifier.weight(1f)){state=if(page==0 && playlist==null && libraryTab==2)state.playQueued(track.id)else state.select(track.id)}
                Text(ampDemoTime(track.duration),color=Color.White,fontSize=18.sp)
                AmpLine(if(track.id in state.favourites)"♥" else "♡"){state=state.toggleFavourite(track.id)}
                AmpLine("Add to playlist"){demoAddSong=track;demoAdd=null}
                AmpLine("+ Queue"){state=state.enqueue(track.id)}
            }}
        }
    }
    if(demoAdd!=null || demoAddSong!=null)no.aura.os.v2.overlay.CanvasDialog({demoAdd=null;demoAddSong=null}) {
        val first=remember {FocusRequester()}
        LaunchedEffect(Unit){withFrameNanos{};first.requestFocus()}
        Column(Modifier.width(600.dp).background(AuraColors.Surface,RoundedCornerShape(20.dp)).padding(24.dp)) {
            Text("Add to playlist · Demo",color=Color.White,fontSize=28.sp)
            session.demoPlaylists.keys.forEachIndexed {index,name->AmpLine(name,modifier=if(index==0)Modifier.focusRequester(first)else Modifier) {
                val songs=demoAddSong?.let{listOf(it.id)} ?: AmpDemoCatalog.tracks.drop(session.album).take(2).map{it.id}
                session.demoPlaylists=session.demoPlaylists+(name to (session.demoPlaylists[name].orEmpty()+songs).distinct())
                demoAdd=null;demoAddSong=null;scope.launch{withFrameNanos{};runCatching{entry.requestFocus()}}
            }}
            AmpLine("Cancel"){demoAdd=null;demoAddSong=null;scope.launch{withFrameNanos{};runCatching{entry.requestFocus()}}}
        }
    }
    if(saver)AmpScreensaver(state.selected.title,state.selected.artist,state.playing,{state=state.copy(playing=!state.playing)},{saver=false;scope.launch{withFrameNanos{};runCatching{player.requestFocus()}}},{cover(state.selected.art,it)})
}
