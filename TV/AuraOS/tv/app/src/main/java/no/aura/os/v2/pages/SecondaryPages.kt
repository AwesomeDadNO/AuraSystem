@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import no.aura.os.v2.apps.AppGridGeometry
import no.aura.os.v2.apps.ShortcutBadge
import no.aura.os.v2.apps.AppsSnapshot
import no.aura.os.v2.apps.LaunchFocusTracker
import no.aura.os.v2.apps.LaunchableApp
import no.aura.os.v2.data.DataMode
import no.aura.os.v2.cards.CompactCard
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.DemoCatalog
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.demo.ServiceEntry
import no.aura.os.v2.demo.ServiceState
import no.aura.os.v2.demo.ServicesDemo
import no.aura.os.v2.shell.Route
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/** Local, illustrative state of the secondary demo views (Services, Search query). */
@Stable
class SecondaryUiState {
    val services = mutableStateMapOf<String, ServiceEntry>().apply { (ServicesDemo.apps + ServicesDemo.sources).forEach { put(it.name, it) } }
    var query by mutableStateOf("")
}

/** Real Mode wiring for the Services and All Apps pages (installed apps only in F1). Null = the host has no real source; pages stay demo. */
class RealAppsUi(
    val mode: DataMode, val snapshot: AppsSnapshot?, val tracker: LaunchFocusTracker, val resumeTick: Int,
    val onLaunchApp: (LaunchableApp) -> Unit, val onLaunchService: (ServiceEntry) -> Unit,
    val appBanner: ((String) -> android.graphics.Bitmap?)? = null,
) { val real: Boolean get() = mode == DataMode.REAL }

/**
 * Minimal secondary demonstration views (Services, All Apps, Search, Settings, Profile, Saved, Library) built from the shared panel/card
 * components. They carry no approved design of their own and are clearly labelled as demo views.
 */
@Composable
fun SecondaryPage(route: Route, catalog: DemoCatalog, ui: SecondaryUiState, entry: FocusRequester, onNavigate: (Route) -> Unit, onDialog: (DemoDialog) -> Unit, modifier: Modifier = Modifier, realUi: RealAppsUi? = null, onProfileAction: (String) -> Unit = {}) {
    val realPage = realUi?.real == true && (route == Route.SERVICES || route == Route.ALL_APPS)
    Column(modifier.fillMaxSize().padding(start = 150.dp, top = 120.dp, end = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(route.title, style = AuraTypography.PageTitle)
        if (realPage) DemoTag("Real mode · detected on this device") else DemoTag()
        when (route) {
            Route.SERVICES -> if (realPage) RealServicesView(realUi!!, entry, onDialog) else ServicesView(ui, entry, onDialog)
            Route.ALL_APPS -> if (realPage) RealAppsGrid(realUi!!, entry) else Tiles(catalog.apps, entry, { false }, "", "", columns = AppGridGeometry.COLUMNS) { name -> Route.values().firstOrNull { it.title == name }?.let(onNavigate) }
            Route.SEARCH -> {
                Text(if (ui.query.isEmpty()) "Type to search (demo) — pick a suggestion:" else "Results for \"${ui.query}\" (demo)", style = AuraTypography.Body, color = AuraColors.TextSecondary)
                Tiles(listOf("Orbital", "The Silver Realm", "Velvet Horizon", "Northbridge FC", "Aurora Concert"), entry, { false }, "", "") { ui.query = it; onDialog(DemoDialog("Search (demo)", listOf("\"$it\" — illustrative result list.", "No search backend is used."))) }
            }
            Route.PROFILE -> Tiles(listOf("Switch profile", "Parental controls", "Watch history", "Sign out"), entry, { false }, "", "") {
                when (it) { "Switch profile" -> onProfileAction("users.switch"); "Parental controls" -> onProfileAction("users.kids"); else -> onDialog(DemoDialog.simple(it)) }
            }
            else -> SavedRow(catalog, entry, onDialog)   // SAVED / LIBRARY
        }
    }
}

@Composable
private fun ServicesView(ui: SecondaryUiState, entry: FocusRequester, onDialog: (DemoDialog) -> Unit) {
    Text("Illustrative data only. Services lists external apps and media sources; it is not an AURA subscription store.", style = AuraTypography.Body, color = AuraColors.TextSecondary)
    Text("Installed apps", style = AuraTypography.Label)
    ServiceTiles(ServicesDemo.apps, entry, { ui.services[it.name] ?: it }) { cur, base ->
        if (cur.state == ServiceState.UNAVAILABLE) onDialog(DemoDialog("${cur.name} (demo)", listOf("Unavailable in this illustrative data set."))) else ui.services[base.name] = cur.toggled()
    }
    Text("Connected media sources", style = AuraTypography.Label)
    ServiceTiles(ServicesDemo.sources, null, { ui.services[it.name] ?: it }) { cur, base ->
        if (cur.state == ServiceState.UNAVAILABLE) onDialog(DemoDialog("${cur.name} (demo)", listOf("Unavailable in this illustrative data set."))) else ui.services[base.name] = cur.toggled()
    }
}

/** Real Mode Services: streaming apps show detected Installed / Not installed; media sources are Not configured (no integration yet). */
@Composable
private fun RealServicesView(r: RealAppsUi, entry: FocusRequester, onDialog: (DemoDialog) -> Unit) {
    val snap = r.snapshot
    Text("Services lists external apps and media sources. Installed means the app is on this device; it does not mean connected.", style = AuraTypography.Body, color = AuraColors.TextSecondary)
    if (snap == null) { Text("Checking installed apps…", style = AuraTypography.Label); return }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(r.resumeTick, snap) { r.tracker.consumeOnResume()?.removePrefix("svc:")?.let { k -> runCatching { (if (k == snap.streaming.firstOrNull()?.name) entry else requesters[k])?.requestFocus() } } }
    Text("Installed apps", style = AuraTypography.Label)
    ServiceTiles(snap.streaming, entry, { it }, requesters = requesters) { cur, _ ->
        when (cur.state) {
            ServiceState.INSTALLED -> r.onLaunchService(cur)
            else -> onDialog(DemoDialog(cur.name, listOf("${cur.name} isn't installed on this device."), footer = null))
        }
    }
    Text("Media sources", style = AuraTypography.Label)
    ServiceTiles(snap.sources, null, { it }) { cur, _ -> onDialog(DemoDialog(cur.name, listOf("${cur.name} is not configured. This integration isn't available yet."), footer = null)) }
}

@Composable
private fun ServiceTiles(
    items: List<ServiceEntry>, entry: FocusRequester?, resolve: (ServiceEntry) -> ServiceEntry,
    requesters: MutableMap<String, FocusRequester>? = null, onClick: (cur: ServiceEntry, base: ServiceEntry) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEachIndexed { i, base ->
            val cur = resolve(base)
            val active = cur.state == ServiceState.INSTALLED || cur.state == ServiceState.CONNECTED
            val fr = if (i == 0 && entry != null) entry else requesters?.getOrPut(base.name) { FocusRequester() }
            Column(
                Modifier.then(if (fr != null) Modifier.focusRequester(fr) else Modifier).size(300.dp, 96.dp)
                    .auraFocusable(shape) { onClick(cur, base) }
                    .panel(shape, if (active) AuraColors.NavPill else AuraColors.Surface).padding(18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(cur.name, style = AuraTypography.CardTitle.copy(fontSize = 22.sp), maxLines = 1)
                Text(cur.state.label, style = AuraTypography.Label)
            }
        }
    }
}

/** Real Mode All Apps: actual launchable Android TV apps (one per package). AuraOS sections stay in the top navigation. */
@Composable
private fun RealAppsGrid(r: RealAppsUi, entry: FocusRequester) {
    val snap = r.snapshot
    if (snap == null) { Text("Checking installed apps…", style = AuraTypography.Label); return }
    if (snap.apps.isEmpty()) { Text("No launchable apps were found on this device.", style = AuraTypography.Body, color = AuraColors.TextSecondary); return }
    val shape = RoundedCornerShape(12.dp)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(r.resumeTick, snap) { r.tracker.consumeOnResume()?.removePrefix("app:")?.let { k -> runCatching { (if (k == snap.apps.first().packageName) entry else requesters[k])?.requestFocus() } } }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(snap.apps.chunked(AppGridGeometry.COLUMNS), key = { it.first().packageName }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { app ->
                    val fr = if (app.packageName == snap.apps.first().packageName) entry else requesters.getOrPut(app.packageName) { FocusRequester() }
                    val image by produceState<android.graphics.Bitmap?>(null, app.packageName) {
                        value = withContext(Dispatchers.IO) { r.appBanner?.invoke(app.packageName) }
                    }
                    Box(
                        Modifier.focusRequester(fr).size(AppGridGeometry.WIDTH.dp, AppGridGeometry.HEIGHT.dp)
                            .auraFocusable(shape) { r.onLaunchApp(app) }
                            .clip(shape).background(if (no.aura.os.v2.apps.ShortcutBrand.forPackage(app.packageName) != null) androidx.compose.ui.graphics.Color.Transparent else AuraColors.Surface)
                    ) {
                        ShortcutBadge(app.packageName, app.label, Modifier.fillMaxSize()) {
                        image?.let {
                            Image(it.asImageBitmap(), contentDescription = app.label,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } ?: Text(app.label, style = AuraTypography.CardTitle.copy(fontSize = 22.sp),
                            maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tiles(labels: List<String>, entry: FocusRequester, on: (String) -> Boolean, onText: String, offText: String, columns: Int = 4, onClick: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    FlowRowSimple(labels.chunked(columns)) { i, label ->
        val state = on(label)
        Column(
            Modifier.then(if (label == labels.first()) Modifier.focusRequester(entry) else Modifier).size(300.dp, 110.dp).auraFocusable(shape) { onClick(label) }
                .panel(shape, if (state) AuraColors.NavPill else AuraColors.Surface).padding(18.dp), verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = AuraTypography.CardTitle.copy(fontSize = 22.sp), maxLines = 1)
            val sub = if (state) onText else offText
            if (sub.isNotEmpty()) Text(sub, style = AuraTypography.Label)
        }
    }
}

@Composable
private fun FlowRowSimple(rows: List<List<String>>, tile: @Composable (Int, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEachIndexed { r, row -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEachIndexed { i, l -> tile(r * 4 + i, l) } } }
    }
}

@Composable
private fun SavedRow(catalog: DemoCatalog, entry: FocusRequester, onDialog: (DemoDialog) -> Unit) {
    val items = catalog.home.shelves[1].items
    Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, c -> CompactCard(c.item, Modifier.then(if (i == 0) Modifier.focusRequester(entry) else Modifier).leftToRail(i == 0)) { onDialog(DemoDialog.simple("Open \"${c.item.title}\"")) } }
    }
}
