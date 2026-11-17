package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import no.aura.os.v2.apps.LaunchableApp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.livetv.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only explicit user sources and verified launchable apps. Guide entries never imply provider entitlement. */
@Composable
fun RealLiveTvPage(
    config:LiveTvConfiguration,state:LiveTvLoadState,playlists:List<UserPlaylist>,installedApps:List<LaunchableApp>,
    mappings:Map<String,String>,entry:FocusRequester,
    onMode:(LiveTvSourceMode)->Unit,onAddPlaylist:(String,String)->Boolean,onRemovePlaylist:(String)->Unit,
    onEpg:(String?)->Boolean,onCountry:(String)->Boolean,onRegion:(String)->Boolean,onTimezone:(String)->Boolean,
    onMap:(String,String?)->Boolean,onPlay:(PlaylistChannel)->Unit,onLaunch:(LaunchableApp)->Unit,onRefresh:()->Unit,
) {
    var playlistName by remember { mutableStateOf("") };var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") };var country by remember(config.country) { mutableStateOf(config.country) }
    var region by remember(config.region) { mutableStateOf(config.region) };var timezone by remember(config.timezone) { mutableStateOf(config.timezone) }
    var message by remember { mutableStateOf<String?>(null) }
    val zone=runCatching { ZoneId.of(config.timezone) }.getOrDefault(ZoneId.systemDefault())
    val format=remember(zone) { DateTimeFormatter.ofPattern("d MMM HH:mm z").withZone(zone) }
    val programmesByChannel=remember(state.guide) { state.guide?.programmes.orEmpty().groupBy { it.channelId } }
    LazyColumn(Modifier.fillMaxSize().padding(start=144.dp,top=132.dp,end=72.dp),
        contentPadding=PaddingValues(bottom=136.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("Live TV",style=AuraTypography.PageTitle) }
        item { Text("Use your own HTTPS playlist and XMLTV guide, or launch an installed TV app.",style=AuraTypography.Body) }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                LiveControl((if(config.mode==LiveTvSourceMode.IPTV_PLAYLIST) "● " else "")+"Use my IPTV playlists",Modifier.focusRequester(entry)) { onMode(LiveTvSourceMode.IPTV_PLAYLIST) }
                LiveControl((if(config.mode==LiveTvSourceMode.INSTALLED_TV_APPS) "● " else "")+"Use installed Live TV apps") { onMode(LiveTvSourceMode.INSTALLED_TV_APPS) }
            }
        }
        item { Text("Switching modes keeps your saved playlists and guide. Credentials in URL queries are encrypted for this profile. Public HTTPS sources only; local HTTP sources are unsupported.",style=AuraTypography.Label) }
        message?.let { item { Text(it,style=AuraTypography.Label,color=AuraColors.Focus) } }
        if(config.mode==LiveTvSourceMode.IPTV_PLAYLIST) {
            item { Text("Your playlists",style=AuraTypography.SectionTitle) }
            items(playlists,key={"source:"+it.id}) { source ->
                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(source.name,style=AuraTypography.CardTitle,modifier=Modifier.weight(1f))
                    LiveControl("Remove playlist") { onRemovePlaylist(source.id) }
                }
            }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    LiveField("Playlist name",playlistName,{playlistName=it})
                    LiveField("HTTPS M3U URL",playlistUrl,{playlistUrl=it},secret=true)
                    LiveControl("Save playlist") {
                        if(onAddPlaylist(playlistName.trim(),playlistUrl.trim())) { playlistName="";playlistUrl="";message="Playlist saved." }
                        else message="Could not save. Check the name, public HTTPS URL and encrypted storage availability."
                    }
                }
            }
        } else {
            item { Text("Installed TV apps",style=AuraTypography.SectionTitle) }
            if(installedApps.isEmpty())item { Text("No supported launchable TV apps were found.",style=AuraTypography.Body) }
            items(installedApps,key={"app:"+it.packageName}) { app -> LiveControl("Open "+app.label) { onLaunch(app) } }
            item { Text("Apps open their own interface. No channel lineup or tuning capability is inferred from installation.",style=AuraTypography.Label) }
        }
        item { Text("Independent programme guide",style=AuraTypography.SectionTitle) }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(config.epgFeedConfigured) "An encrypted XMLTV source is saved." else "No XMLTV source is configured.",style=AuraTypography.Label)
                LiveField("HTTPS XMLTV URL",epgUrl,{epgUrl=it},secret=true)
                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    LiveControl("Save guide source") { if(onEpg(epgUrl.trim())){epgUrl="";message="Guide source saved."}else message="Could not save the HTTPS XMLTV source." }
                    LiveControl("Remove guide source") { if(onEpg(null))message="Guide source removed." }
                }
                LiveField("Country code (for example NO)",country,{country=it})
                LiveField("Region",region,{region=it})
                LiveField("Timezone (for example Europe/Oslo)",timezone,{timezone=it})
                LiveControl("Save guide location") {
                    val a=onCountry(country.trim().uppercase());val b=onRegion(region.trim());val c=onTimezone(timezone.trim())
                    message=if(a&&b&&c) "Guide location saved. Programme times use the selected timezone." else "Check the two-letter country code and IANA timezone."
                }
                Text("Location changes how the guide is displayed. It does not discover a channel lineup or purchase access.",style=AuraTypography.Label)
                LiveControl("Refresh sources",onClick=onRefresh)
            }
        }
        if(state.loading)item { Text("Loading your sources…",style=AuraTypography.Body) }
        state.error?.let { item { Text(it,style=AuraTypography.Body) } }
        state.warnings.forEach { warning -> item { Text(warning,style=AuraTypography.Label) } }
        if(config.mode==LiveTvSourceMode.IPTV_PLAYLIST) {
            item { Text("Channels",style=AuraTypography.SectionTitle) }
            if(state.channels.isEmpty() && !state.loading)item { Text("No channels are available from your saved playlists.",style=AuraTypography.Body) }
            items(state.channels,key={"channel:"+it.key}) { channel ->
                var mapId by remember(channel.key,mappings[channel.key]) { mutableStateOf(mappings[channel.key] ?: channel.epgId.orEmpty()) }
                val linked=mappings[channel.key] ?: channel.epgId
                val programmes=programmesByChannel[linked].orEmpty().filter { it.end>Instant.now() }.take(3)
                Column(Modifier.fillMaxWidth().background(AuraColors.Surface,RoundedCornerShape(12.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(channel.name+(channel.group?.let { " · $it" } ?: ""),style=AuraTypography.CardTitle)
                    LiveControl("Play channel") { onPlay(channel) }
                    if(programmes.isEmpty())Text("No matching guide programme.",style=AuraTypography.Label)
                    programmes.forEach { Text(format.format(it.start)+" · "+it.title,style=AuraTypography.Label) }
                    LiveField("XMLTV channel ID",mapId,{mapId=it})
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        LiveControl("Map guide channel") { message=if(onMap(channel.key,mapId.trim().takeIf { it.isNotEmpty() }))"Guide mapping saved." else "Could not save guide mapping." }
                        LiveControl("Reset mapping") { onMap(channel.key,null) }
                    }
                }
            }
        } else {
            val guide=state.guide
            if(guide!=null) {
                item { Text("Guide · not linked to an app",style=AuraTypography.SectionTitle) }
                items(guide.channels.take(200),key={"guide:"+it.id}) { channel ->
                    val programmes=programmesByChannel[channel.id].orEmpty().filter { it.end>Instant.now() }.take(3)
                    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(channel.name+" · Not linked to app",style=AuraTypography.CardTitle)
                        programmes.forEach { Text(format.format(it.start)+" · "+it.title,style=AuraTypography.Label) }
                    }
                }
            }
        }
        item { Text("Playback depends on your source rights and format support. DRM and paid-provider entitlement are not supplied by AuraOS.",style=AuraTypography.Label) }
    }
}
@Composable
private fun LiveControl(text:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    Text(text,style=AuraTypography.Button,color=AuraColors.Focus,
        modifier=modifier.auraFocusable(RoundedCornerShape(10.dp),onClick=onClick)
            .background(AuraColors.Surface,RoundedCornerShape(10.dp)).padding(horizontal=16.dp,vertical=12.dp))
}
@Composable
private fun LiveField(label:String,value:String,onValue:(String)->Unit,secret:Boolean=false) {
    OutlinedTextField(value,onValueChange={if(it.length<=4096)onValue(it)},label={Text(label)},singleLine=true,
        visualTransformation=if(secret)PasswordVisualTransformation() else VisualTransformation.None,
        modifier=Modifier.widthIn(min=400.dp,max=760.dp).fillMaxWidth())
}
