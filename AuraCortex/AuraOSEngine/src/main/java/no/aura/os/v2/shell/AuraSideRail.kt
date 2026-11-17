package no.aura.os.v2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.theme.AuraColors

/**
 * Narrow icon-only left rail (Home / Saved / Library), 94 wide, transparent over the page. Icons are 28 with 70 pitch, first centre at y=265
 * (measured on the mockups). Overlay: it never changes the page's layout width. Labels are exposed as content descriptions only.
 */
@Composable
fun AuraSideRail(active: Route, railEntry: FocusRequester, pageEntry: FocusRequester, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier, flags: ModuleFlags = ModuleFlags()) {
    val items = NavModel.rail.filter { route ->
        when (route) { Route.AURAAMP -> flags.auraAmp; Route.AURATUBE -> flags.auraTube; else -> true }
    }
    Column(
        modifier.padding(start = 20.dp, top = 132.dp).width(74.dp)
            .padding(horizontal = 11.dp, vertical = 18.dp)
            .focusProperties { right = pageEntry },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items.forEachIndexed { i, r ->
            val interaction = rememberNavigationInteraction()
            val selected = NavModel.isSelected(r, active)
            val tint = if(selected || interaction.focused) AuraColors.Focus.copy(alpha=if(interaction.focused) 1f else .8f) else AuraColors.TextPrimary
            Box(
                (if (r == (active.takeIf { it in items } ?: items.firstOrNull())) Modifier.focusRequester(railEntry) else Modifier)
                    .size(52.dp).auraNavigationFocusable(interaction, selected, underline = false) { onNavigate(r) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(r.icon(), r.title, tint = tint, modifier = Modifier.size(28.dp))
            }
        }
    }
}
