package no.aura.os.v2.shell

import androidx.compose.material.icons.rounded.Notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.apps.ShortcutBadge
import no.aura.os.v2.brand.AuraWordmark
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

fun Route.icon(): ImageVector = when (this) {
    Route.ALL_APPS -> Icons.Rounded.Apps; Route.NOTIFICATIONS -> Icons.Rounded.Notifications
    Route.SEARCH -> Icons.Rounded.Search; Route.SETTINGS -> Icons.Rounded.Settings
    Route.PROFILE -> Icons.Rounded.AccountCircle; Route.HOME -> Icons.Rounded.Home; Route.SAVED -> Icons.Rounded.BookmarkBorder
    Route.LIBRARY -> Icons.Rounded.VideoLibrary; Route.AURAAMP -> Icons.Rounded.GraphicEq; Route.AURATUBE -> Icons.Rounded.SmartDisplay; else -> Icons.Rounded.Home
}

/** Icon of a main navigation destination (styles "Icons + text" / "Aura apps"). */
fun Route.navIcon(): ImageVector = when (this) {
    Route.HOME -> Icons.Rounded.Home; Route.MOVIES -> Icons.Rounded.Movie; Route.TV_SHOWS -> Icons.Rounded.Tv
    Route.SPORTS -> Icons.Rounded.EmojiEvents; Route.LIVE_TV -> Icons.Rounded.LiveTv; Route.AURABOX -> Icons.Rounded.Inventory2
    Route.AURAAMP -> Icons.Rounded.GraphicEq; Route.AURATUBE -> Icons.Rounded.SmartDisplay; Route.SERVICES -> Icons.Rounded.Widgets
    else -> icon()
}

/**
 * Pinned global navigation with orange underlines for the selected destination and actual focus.
 * Hero chrome starts at 80% opacity and becomes opaque when its outer page scrolls.
 * One renderer for all saved legacy preferences: destination labels are text-only; app shortcuts retain brand artwork.
 */
@Composable
fun AuraTopNavigation(
    active: Route, layout: List<List<NavItemModel>>, itemPadding: androidx.compose.ui.unit.Dp, homeRequester: FocusRequester, pageEntry: FocusRequester,
    onNavigate: (Route) -> Unit, modifier: Modifier = Modifier,
    appIcon: @Composable (AppShortcut, Modifier) -> Unit = { _, _ -> }, shortcutRequester: (String) -> FocusRequester = { FocusRequester() },
    onShortcut: (AppShortcut) -> Unit = {}, unreadNotifications: Int = 0,
    scrolled: Boolean = LocalChromeScroll.current.scrolled,
) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(ChromePolicy.opacity(scrolled),
        androidx.compose.animation.core.tween(if (no.aura.os.v2.theme.LocalReduceMotion.current) 0 else 220), label = "chromeAlpha")
    Row(
        modifier.shadow(if (scrolled) 4.dp else 8.dp).background(AuraColors.Background.copy(alpha = alpha)).verticalLinks(down = pageEntry).fillMaxWidth().height(AuraDimensions.TopNavHeight)
            .padding(horizontal = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraWordmark()
        Spacer(Modifier.width(28.dp))
        layout.forEachIndexed { gi, group ->
            if (gi > 0) NavDivider(Modifier.padding(horizontal = 12.dp), 28.dp)
            group.forEach { item ->
                when (item) {
                    is NavItemModel.Destination -> NavItem(item.route, item.showIcon, NavModel.isSelected(item.route, active), if (item.route == Route.HOME) Modifier.focusRequester(homeRequester) else Modifier, itemPadding) { onNavigate(item.route) }
                    is NavItemModel.App -> AppShortcutItem(item.shortcut, Modifier.focusRequester(shortcutRequester(item.shortcut.packageName)), itemPadding, appIcon) { onShortcut(item.shortcut) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        NavModel.rightActions.forEach { r -> IconAction(r, NavModel.isSelected(r, active), if (r == Route.NOTIFICATIONS) unreadNotifications else 0) { onNavigate(r) } }
    }
}

@Composable
private fun NavDivider(modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    Box(modifier.width(1.dp).height(height).background(AuraColors.Border))
}

@Composable
private fun NavItem(route: Route, withIcon: Boolean, selected: Boolean, modifier: Modifier, hPadding: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val interaction = rememberNavigationInteraction()
    val tint = AuraColors.TextPrimary
    Row(
        modifier.padding(horizontal = 2.dp).auraNavigationFocusable(interaction, selected, onClick = onClick)
            .padding(horizontal = hPadding, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label next to it already names the destination, so the icon itself is decorative for accessibility (contentDescription = null).
        // Destination labels are text-only in every saved navigation style.
        Text(route.title, style = AuraTypography.Nav.copy(fontSize = NavMetrics.FONT.sp), color = tint, maxLines = 1, softWrap = false)
    }
}

/** Landscape shortcut badge; unknown packages use the caller's contained native artwork. */
@Composable
private fun AppShortcutItem(shortcut: AppShortcut, modifier: Modifier, hPadding: androidx.compose.ui.unit.Dp, appIcon: @Composable (AppShortcut, Modifier) -> Unit, onClick: () -> Unit) {
    val interaction = rememberNavigationInteraction()
    Box(
        modifier.padding(horizontal = 2.dp).auraNavigationFocusable(interaction, onClick = onClick)
            .padding(horizontal = NavMetrics.SHORTCUT_PADDING.dp, vertical = 4.dp)
            .semantics { contentDescription = shortcut.label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        ShortcutBadge(shortcut.packageName, shortcut.label,
            Modifier.width(NavMetrics.SHORTCUT_ICON.dp).height((NavMetrics.SHORTCUT_ICON * 9f / 16f).dp)) {
            appIcon(shortcut, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AllAppsAction(selected: Boolean, onClick: () -> Unit) {
    val interaction = rememberNavigationInteraction()
    val tint = AuraColors.TextPrimary
    Row(
        Modifier.auraNavigationFocusable(interaction, selected, onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Route.ALL_APPS.icon(), null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("All Apps", color = tint, style = AuraTypography.Nav.copy(fontSize = NavMetrics.FONT.sp), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun IconAction(route: Route, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val interaction = rememberNavigationInteraction()
    val tint = AuraColors.TextPrimary
    Box(
        Modifier.padding(start = 10.dp).auraNavigationFocusable(interaction, selected, onClick = onClick).padding(10.dp),
    ) {
        Icon(route.icon(), route.title + if (badge > 0) " · $badge unread" else "", tint = tint, modifier = Modifier.size(28.dp))
        if (badge > 0) Text(badge.coerceAtMost(99).toString(), style = AuraTypography.Label, color = AuraColors.TextPrimary, modifier = Modifier.align(Alignment.TopEnd))
    }
}
