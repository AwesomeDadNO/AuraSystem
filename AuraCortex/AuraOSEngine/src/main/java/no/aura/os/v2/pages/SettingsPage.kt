@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import no.aura.os.v2.shell.auraNavigationFocusable
import no.aura.os.v2.shell.rememberNavigationInteraction
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.settings.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/**
 * Settings: category list on the left, rows on the right. DPAD: UP/DOWN moves through categories (the pane follows focus), RIGHT enters the
 * pane, LEFT returns to the category (or the rail from the category list). Rows that do not work yet are visibly disabled ("Coming later")
 * and only open an explanation. Built from the shared panel/focus components; no new visual language.
 */
@Composable
fun SettingsPage(state: SettingsState, entry: FocusRequester, onToggle: (String) -> Unit, onAction: (String) -> Unit, onDialog: (DemoDialog) -> Unit, onAuraConnect: () -> Unit, modifier: Modifier = Modifier, initialCategory: SettingsCategory = SettingsCategory.GENERAL, initialItemId:String?=null) {
    var selected by rememberSaveable(initialCategory,initialItemId) { mutableStateOf(initialCategory) }
    val categoryRequesters = remember { SettingsCategory.values().associateWith { if (it == initialCategory) entry else FocusRequester() } }
    val paneEntry = remember(selected) { FocusRequester() }
    val rows = remember(selected, state) { SettingsPresenter.rows(selected, state) }
    val shape = RoundedCornerShape(10.dp)
    val list=rememberLazyListState()
    val requestedIndex=rows.indexOfFirst { it.item.id==initialItemId }.coerceAtLeast(0)
    LaunchedEffect(selected,initialItemId) {
        if(selected!=SettingsCategory.AURASPORT) {
            list.scrollToItem(requestedIndex)
            if(initialItemId!=null && rows.any { it.item.id==initialItemId }) {
                withFrameNanos{}; withFrameNanos{}; runCatching { paneEntry.requestFocus() }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(start = 150.dp, top = 120.dp, end = 60.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column {
                Text("Settings",style=AuraTypography.PageTitle)
                Text("Personalise AuraOS · "+state.profiles.active.name,style=AuraTypography.Metadata,modifier=Modifier.padding(top=6.dp))
            }
            Text("Android TV settings  ↗",style=AuraTypography.Button,modifier=Modifier.auraFocusable(shape,onClick={onAction("general.android_tv")})
                .panel(shape,AuraColors.Surface).padding(horizontal=24.dp,vertical=16.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsCategory.values().forEach { cat ->
                    val interaction=rememberNavigationInteraction()
                    Text(
                        cat.title, style = AuraTypography.Nav.copy(fontSize = 22.sp), maxLines = 1,
                        modifier = Modifier.fillMaxWidth().focusRequester(categoryRequesters.getValue(cat)).leftToRail(true).focusProperties { right = paneEntry }
                            .onFocusChanged { if(it.isFocused) selected=cat }
                            .auraNavigationFocusable(interaction,cat==selected) { selected=cat }
                            .padding(horizontal = 22.dp, vertical = 16.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(AuraColors.Surface.copy(alpha=.55f),RoundedCornerShape(18.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(selected.title,style=AuraTypography.SectionTitle)
                Text(categorySummary(selected),style=AuraTypography.Metadata,color=AuraColors.TextSecondary)
            if (selected == SettingsCategory.AURASPORT) Box(Modifier.fillMaxWidth().weight(1f)) {
                AuraSportSettings(state.profiles.activeId, paneEntry, categoryRequesters.getValue(selected))
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f), state=list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.item.id }) { row ->
                    val first = row.item.id == rows.getOrNull(requestedIndex)?.item?.id
                    SettingsRowView(
                        row, shape,
                        Modifier.focusProperties { left = categoryRequesters.getValue(selected) }.then(if (first) Modifier.focusRequester(paneEntry) else Modifier),
                    ) {
                        when {
                            !row.enabled -> onDialog(DemoDialog(row.item.title, listOf(row.value + ". This setting is not available yet and does nothing."), footer = null))
                            row.item.kind == ItemKind.TOGGLE -> onToggle(row.item.id)
                            row.item.kind == ItemKind.ACTION -> onAction(row.item.id)
                            row.item.id == "auraconnect.pairing_code" -> onAuraConnect()
                            row.item.id.startsWith(SettingsCatalog.CONNECTION_PREFIX) ->
                                onDialog(DemoDialog(row.item.title, SettingsPresenter.connectionLines(SettingsPresenter.status(row.item, state)), footer = null))
                        }
                    }
                }
            }
            }
        }
    }
}

private fun categorySummary(category:SettingsCategory):String = when(category) {
    SettingsCategory.GENERAL -> "Home screen, playback, accessibility and device preferences"
    SettingsCategory.MODULES -> "Choose which AuraOS modules appear"
    SettingsCategory.AURASPORT -> "Leagues, favorite teams, spoilers and match widgets"
    SettingsCategory.CONNECTIONS -> "Connected apps, media servers and automatic Simkl imports"
    SettingsCategory.USERS -> "Profiles, accounts and parental controls"
    SettingsCategory.STATUS -> "Device, connection and application status"
    SettingsCategory.ABOUT -> "Version and build information"
}

@Composable
private fun SettingsRowView(row: SettingsRow, shape: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(80.dp).alpha(if (row.enabled) 1f else 0.55f).auraFocusable(shape, onClick = onClick)
            .panel(shape, if (row.on == true) AuraColors.NavPill else AuraColors.Surface).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.item.title, style = AuraTypography.CardTitle.copy(fontSize = 22.sp), maxLines = 1, modifier = Modifier.weight(1f))
        Text((if(row.item.kind==ItemKind.TOGGLE) if(row.on==true) "✓  " else "○  " else "")+row.value, style = AuraTypography.Label.copy(fontSize = 18.sp, color = if (row.enabled) AuraColors.TextPrimary else AuraColors.TextSecondary), maxLines = 1)
    }
}
