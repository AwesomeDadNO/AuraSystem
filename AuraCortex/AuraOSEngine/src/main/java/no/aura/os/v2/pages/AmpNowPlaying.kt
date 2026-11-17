package no.aura.os.v2.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.amp.ampDemoTime
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.shell.auraNavigationFocusable
import no.aura.os.v2.shell.rememberNavigationInteraction
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraPalette

@Composable internal fun AmpWordmark() {
    Row(Modifier.height(56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Rounded.GraphicEq,null,tint=AuraColors.Focus,modifier=Modifier.size(48.dp))
        Row { Text("Aura",color=Color.White,fontSize=40.sp,fontWeight=FontWeight.Bold);Text("Amp",color=AuraColors.Focus,fontSize=40.sp,fontWeight=FontWeight.Bold) }
        Text("INSIDE AURAOS",color=AuraColors.TextSecondary,fontSize=16.sp,letterSpacing=3.sp)
    }
}
@Composable internal fun AmpLine(label:String,selected:Boolean=false,modifier:Modifier=Modifier,onClick:()->Unit) {
    val interaction=rememberNavigationInteraction()
    Text(label,color=Color.White,fontSize=26.sp,maxLines=1,modifier=modifier.auraNavigationFocusable(interaction,selected,onClick=onClick).padding(horizontal=10.dp,vertical=12.dp))
}
/** Presentation only. Provider, profile and library ownership remain with existing callers. */
@Composable internal fun AmpNowPlaying(
    title:String,artist:String,position:Long,duration:Long,playing:Boolean,shuffle:Boolean,repeat:Boolean,
    hasTrack:Boolean,status:String?,entry:FocusRequester,libraryEntry:FocusRequester,
    onToggle:()->Unit,onPrevious:()->Unit,onNext:()->Unit,onShuffle:()->Unit,onRepeat:()->Unit,
    onSeek:(Long)->Unit,onScreensaver:()->Unit,artwork:@Composable (Modifier)->Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=18.dp).height(510.dp),horizontalArrangement=Arrangement.spacedBy(54.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(490.dp).shadow(36.dp,RoundedCornerShape(22.dp),clip=false,ambientColor=AuraColors.Focus.copy(alpha=.3f),spotColor=AuraColors.Focus.copy(alpha=.42f)).clip(RoundedCornerShape(22.dp)).border(1.dp,AuraColors.Focus.copy(alpha=.25f),RoundedCornerShape(22.dp))) {artwork(Modifier.fillMaxSize())}
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp),verticalArrangement=Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(AuraColors.ButtonInactive).semantics{contentDescription="Lyrics unavailable for this source";disabled()}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Lyrics,null,tint=AuraColors.ButtonIcon,modifier=Modifier.size(30.dp));Text("Lyrics unavailable",color=AuraColors.TextMuted,fontSize=22.sp)
                }
                Box(Modifier.padding(horizontal=20.dp).width(1.dp).height(28.dp).background(AuraColors.Border))
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(AuraColors.ButtonInactive).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tv,null,tint=AuraColors.ButtonIcon,modifier=Modifier.size(30.dp))
                    AmpLine("Screensaver",onClick=onScreensaver)
                }
            }
            Column {
                Text(title,color=Color.White,fontSize=54.sp,lineHeight=58.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(artist,color=AuraColors.TextSecondary,fontSize=34.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                status?.let {Text(it,color=AuraColors.TextSecondary,fontSize=16.sp,maxLines=2)}
            }
            Column {
                Canvas(Modifier.fillMaxWidth().height(82.dp).onPreviewKeyEvent { e ->
                    if(!hasTrack || e.type!=KeyEventType.KeyDown)false else when(e.key){Key.DirectionLeft->{onSeek(position-10000);true};Key.DirectionRight->{onSeek(position+10000);true};else->false}
                }.semantics{contentDescription="Seek with left and right";progressBarRangeInfo=ProgressBarRangeInfo(position.toFloat(),0f..duration.coerceAtLeast(1).toFloat());setProgress{if(hasTrack){onSeek(it.toLong().coerceIn(0,duration.coerceAtLeast(0)));true}else false}}
                    .then(if(hasTrack)Modifier.auraFocusable(RoundedCornerShape(6.dp),scale=1f,onClick=onToggle)else Modifier)) {
                    val progress=if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f)else 0f
                    val step=size.width/144f
                    repeat(144) {i->
                        val x=(i+.5f)*step
                        val wave=kotlin.math.abs(kotlin.math.sin(i*.29)*kotlin.math.cos(i*.071))
                        val envelope=.2f+.8f*kotlin.math.sin(Math.PI*(i+1)/145).toFloat()
                        val height=(12f+wave.toFloat()*size.height*.78f)*envelope
                        drawRoundRect(if(i/144f<progress)AuraColors.Focus else Color.White.copy(alpha=.3f),Offset(x,size.height/2-height/2),Size(step*.55f,height),CornerRadius(step*.3f))
                    }
                    if(hasTrack) {
                        val x=(progress*size.width).coerceIn(3.dp.toPx(),size.width-3.dp.toPx())
                        drawRoundRect(AuraColors.Focus,Offset(x-2.dp.toPx(),size.height/2-14.dp.toPx()),Size(4.dp.toPx(),28.dp.toPx()),CornerRadius(2.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(ampDemoTime((position/1000).toInt()),color=Color.White,fontSize=22.sp);Text(ampDemoTime((duration/1000).toInt()),color=Color.White,fontSize=22.sp)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                AmpTransport(Icons.Rounded.Shuffle,"Shuffle",selected=shuffle,onClick=onShuffle)
                AmpTransport(Icons.Rounded.SkipPrevious,"Previous song",onClick=onPrevious)
                AmpTransport(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playing)"Pause" else "Play",Modifier.focusRequester(entry).focusProperties{down=libraryEntry},primary=true,enabled=hasTrack,onClick=onToggle)
                AmpTransport(Icons.Rounded.SkipNext,"Next song",onClick=onNext)
                AmpTransport(Icons.Rounded.Repeat,"Repeat",selected=repeat,onClick=onRepeat)
            }
        }
    }
}
@Composable private fun AmpTransport(icon:ImageVector,label:String,modifier:Modifier=Modifier,primary:Boolean=false,selected:Boolean=false,enabled:Boolean=true,onClick:()->Unit) {
    Box(modifier.size(if(primary)124.dp else 86.dp).semantics{role=Role.Button;contentDescription=label;if(!enabled)disabled();if(selected)stateDescription="On"}
        .shadow(if(primary)36.dp else 0.dp,CircleShape,clip=false,ambientColor=AuraColors.Focus.copy(alpha=.3f),spotColor=AuraColors.Focus.copy(alpha=.45f))
        .auraFocusable(CircleShape,scale=1f){if(enabled)onClick()}.clip(CircleShape)
        .background(if(primary)AuraColors.ButtonGradient else Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent)))
        .border(if(primary || selected)2.dp else 0.dp,if(primary)Color.White.copy(alpha=.35f)else if(selected)AuraColors.Focus else Color.Transparent,CircleShape),contentAlignment=Alignment.Center) {
        if(icon==Icons.Rounded.SkipPrevious || icon==Icons.Rounded.SkipNext) {
            Canvas(Modifier.size(52.dp)) {
                val previous=icon==Icons.Rounded.SkipPrevious
                val path=Path().apply {
                    if(previous){moveTo(size.width*.22f,size.height*.5f);lineTo(size.width*.84f,size.height*.12f);lineTo(size.width*.84f,size.height*.88f)}
                    else {moveTo(size.width*.78f,size.height*.5f);lineTo(size.width*.16f,size.height*.12f);lineTo(size.width*.16f,size.height*.88f)}
                    close()
                }
                drawPath(path,Color.White)
                drawRoundRect(Color.White,Offset(if(previous)size.width*.07f else size.width*.86f,size.height*.12f),Size(size.width*.08f,size.height*.76f),CornerRadius(2.dp.toPx()))
            }
        } else Icon(icon,null,tint=if(enabled)Color.White else Color.White.copy(alpha=.4f),modifier=Modifier.size(if(primary)64.dp else 52.dp))
    }
}

/** Explicit presentation mode; does not change the Android system screensaver. */
@Composable internal fun AmpScreensaver(title:String,artist:String,playing:Boolean,onToggle:()->Unit,onClose:()->Unit,artwork:@Composable (Modifier)->Unit) {
    val close=remember {FocusRequester()}
    no.aura.os.v2.overlay.CanvasDialog(onClose,fullScreen=true) {
        LaunchedEffect(Unit){withFrameNanos{};close.requestFocus()}
        Column(Modifier.fillMaxSize().background(AuraColors.Background).padding(50.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            artwork(Modifier.size(420.dp).clip(RoundedCornerShape(24.dp)))
            Text(title,color=Color.White,fontSize=36.sp,fontWeight=FontWeight.Bold,maxLines=2)
            Text(artist,color=AuraColors.TextSecondary,fontSize=24.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(30.dp)) {
                AmpLine(if(playing)"Pause" else "Play",onClick=onToggle)
                AmpLine("Back to music",modifier=Modifier.focusRequester(close),onClick=onClose)
            }
        }
    }
}
