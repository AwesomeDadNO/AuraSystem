package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.amp.AmpAlbum
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.hero.HeroTitleText
import no.aura.os.v2.theme.AuraColors

/** One focus group contains the three actions; decorative layers never steal DPAD focus. */
@Composable internal fun AmpDiscoverHero(
    albums:List<AmpAlbum>,index:Int,liked:Boolean,entry:FocusRequester,onFocus:(Boolean)->Unit,
    onPrevious:()->Unit,onNext:()->Unit,onPlay:()->Unit,onLike:()->Unit,onAdd:()->Unit,
    artwork:@Composable (String?,Modifier)->Unit,
) {
    val shape=RoundedCornerShape(26.dp)
    val current=albums.getOrNull(index.coerceIn(0,(albums.size-1).coerceAtLeast(0))) ?: return
    Column(Modifier.fillMaxWidth().onFocusChanged{onFocus(it.hasFocus)},horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(480.dp)) {
            artwork(current.heroImage ?: current.cover,Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha=.9f),.58f to AuraColors.Background.copy(alpha=.25f),1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent,1f to AuraColors.Background)))
                Column(Modifier.padding(start=42.dp,top=50.dp,bottom=55.dp).width(850.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween) {
                    Text("FEATURED ALBUM",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    HeroTitleText(current.title,width=690.dp,height=120.dp,maxFont=76,minFont=32)
                    Text(current.artist,color=Color.White,fontSize=28.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    current.releaseDate?.takeIf{it.isNotBlank()}?.let {Text(it.take(10),color=AuraColors.TextSecondary,fontSize=18.sp)}
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Row(Modifier.focusRequester(entry).auraFocusable(RoundedCornerShape(12.dp),scale=1f,onClick=onPlay).clip(RoundedCornerShape(12.dp)).background(AuraColors.PrimaryAction).padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.PlayArrow,null,tint=AuraColors.ButtonIcon,modifier=Modifier.size(30.dp));Text("Play album",color=AuraColors.OnPrimaryAction,fontSize=24.sp,fontWeight=FontWeight.Bold)
                        }
                        HeroIcon(if(liked)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,if(liked)"Unlike album" else "Like album",liked,onLike)
                        HeroIcon(Icons.Rounded.PlaylistAdd,"Add album to playlist",false,onAdd)
                    }
                }
            if(albums.size>1)Row(Modifier.align(Alignment.BottomEnd).padding(24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                AmpLine("‹",modifier=Modifier.semantics{contentDescription="Previous featured album"},onClick=onPrevious)
                AmpLine("›",modifier=Modifier.semantics{contentDescription="Next featured album"},onClick=onNext)
            }
        }
        Row(Modifier.padding(top=8.dp,bottom=2.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            albums.forEachIndexed {i,_->Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if(i==index)AuraColors.Focus else Color.White.copy(alpha=.35f)))}
        }
    }
}
@Composable private fun HeroIcon(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,selected:Boolean,onClick:()->Unit) {
    val shape=RoundedCornerShape(12.dp)
    Box(Modifier.size(56.dp).semantics{contentDescription=label}.auraFocusable(shape,scale=1f,onClick=onClick).clip(shape).background(AuraColors.ButtonInactive).border(1.dp,if(selected)AuraColors.Focus else Color.White.copy(alpha=.4f),shape),contentAlignment=Alignment.Center) {
        Icon(icon,null,tint=AuraColors.ButtonIcon,modifier=Modifier.size(30.dp))
    }
}
