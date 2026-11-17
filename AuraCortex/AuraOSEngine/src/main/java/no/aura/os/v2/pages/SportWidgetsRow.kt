package no.aura.os.v2.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.sports.*
import no.aura.os.v2.theme.*

/** Opt-in local demo widgets, never cross-app overlays or actual live scores. */
@Composable
fun SportWidgetsRow(prefs:AuraSportPrefs, entry:FocusRequester, up:FocusRequester, down:FocusRequester, onOpen:()->Unit) {
    val matches=AuraSportDemo.widgets(prefs)
    Column(Modifier.padding(start=137.dp,top=16.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("AuraSport · Mine kampwidgeter · DEMO",style=AuraTypography.SectionTitle)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(end=72.dp,top=4.dp,bottom=4.dp)) {
            itemsIndexed(matches,key={_,match->match.id}) { i,match ->
                Column(Modifier.width(350.dp).height(128.dp).then(if(i==0) Modifier.focusRequester(entry) else Modifier)
                    .focusProperties { this.up=up; this.down=down }.auraFocusable(RoundedCornerShape(12.dp),scale=1f,onClick=onOpen)
                    .panel(RoundedCornerShape(12.dp),AuraColors.Surface).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${match.home} – ${match.away}",style=AuraTypography.CardTitle,maxLines=1)
                    Text(match.result(prefs),style=AuraTypography.SectionTitle)
                    Text(match.status+" · Fiktiv kamp",style=AuraTypography.Label,maxLines=1)
                }
            }
        }
    }
}
