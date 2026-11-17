package no.aura.os.v2.hero

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

object HeroTitleFit {
    /** Largest fitting integer size, bounded even when no candidate fits. */
    fun largest(min:Int,max:Int,fits:(Int)->Boolean):Int {
        require(min>0 && max>=min)
        var low=min;var high=max;var result=min
        while(low<=high) { val mid=(low+high)/2; if(fits(mid)) {result=mid;low=mid+1} else high=mid-1 }
        return result
    }
}
/** Measured two-line bold title; the fixed slot prevents description/action movement. */
@Composable
fun HeroTitleText(title:String, modifier:Modifier=Modifier, width:Dp=960.dp, height:Dp=122.dp, maxFont:Int=86,minFont:Int=24) {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val base=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,color=Color.White,
        shadow=Shadow(Color.Black.copy(alpha=.85f),Offset(2f,3f),8f))
    BoxWithConstraints(modifier.width(width).height(height),contentAlignment=Alignment.CenterStart) {
        val constraints=with(density) { Constraints(maxWidth=maxWidth.roundToPx().coerceAtLeast(1),maxHeight=maxHeight.roundToPx().coerceAtLeast(1)) }
        val font=remember(title,constraints,density,maxFont,minFont) {
            HeroTitleFit.largest(minFont,maxFont) { size ->
                !measurer.measure(title,style=base.copy(fontSize=size.sp,lineHeight=(size*1.05f).sp),constraints=constraints,maxLines=2,softWrap=true,overflow=TextOverflow.Clip).hasVisualOverflow
            }
        }
        Text(title,style=base.copy(fontSize=font.sp,lineHeight=(font*1.05f).sp),maxLines=2,softWrap=true,overflow=TextOverflow.Ellipsis)
    }
}
