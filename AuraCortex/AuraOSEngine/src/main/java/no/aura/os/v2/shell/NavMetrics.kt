package no.aura.os.v2.shell

/**
 * Width budget of the top navigation row on the 1920 canvas, from the measured geometry in [AuraTopNavigation]. It is an ESTIMATE (text
 * width = [CHAR_FACTOR] x font size per character); the emulator check is the authority. It exists so adding a module, an icon style or the
 * external shortcuts can never silently push the right-hand actions off screen: a unit test fails first, and [itemPadding] shrinks the pills
 * (never below [MIN_ITEM_PADDING]) when a row would leave less than [MIN_SLACK] free.
 */
object NavMetrics {
    const val CANVAS = 1920f
    private const val CHAR_FACTOR = 0.56f          // average advance of the Nav face, as a fraction of its 20 sp size (conservative)
    const val FONT = 25f
    const val ITEM_PADDING_H = 23f                 // approved horizontal padding of a nav pill
    const val MIN_ITEM_PADDING = 10f
    const val MIN_SLACK = 24f
    const val ICON = 20f; const val ICON_GAP = 6f  // icon + gap before the label
    const val SHORTCUT_PADDING = 6f
    const val SHORTCUT_ICON = 76f                  // icon-only external app shortcut
    private const val ITEM_MARGIN = 2 * 2f
    private const val SIDE_MARGIN = 2 * 52f
    private const val WORDMARK = 28f * (720f / 126f)   // 28 high bitmap, 720x126
    private const val WORDMARK_DIVIDER = 2 * 26f + 1f
    private const val GROUP_DIVIDER = 2 * 12f + 1f
    private const val RIGHT_ACTIONS = (14f + 1f) + (2 * 14f + 26f + 10f + 8 * CHAR_FACTOR * FONT) + 4 * (10f + 28f + 2 * 10f)

    fun textWidth(s: String) = s.length * CHAR_FACTOR * FONT

    fun estimatedWidth(flags: ModuleFlags, padding: Float = ITEM_PADDING_H, extraWidth: Float = 0f, style: NavStyle = NavStyle.TEXT_ONLY, shortcuts: Int = 0): Float {
        val fakeShortcuts = List(shortcuts.coerceAtMost(NavModel.MAX_SHORTCUTS)) { AppShortcut("p$it", "") }
        val groups = NavModel.layout(flags, style, fakeShortcuts)
        val items = groups.flatten()
        val itemsWidth = items.sumOf { item ->
            when (item) {
                is NavItemModel.Destination -> textWidth(item.route.title) + (if (item.showIcon) ICON + ICON_GAP else 0f)
                is NavItemModel.App -> SHORTCUT_ICON
            }.toDouble() + 2 * (if (item is NavItemModel.App) SHORTCUT_PADDING else padding) + ITEM_MARGIN
        }.toFloat()
        return SIDE_MARGIN + WORDMARK + WORDMARK_DIVIDER + itemsWidth + (groups.size - 1).coerceAtLeast(0) * GROUP_DIVIDER + RIGHT_ACTIONS + extraWidth
    }

    /** Free horizontal space between the last nav item and the right-hand actions. */
    fun slack(flags: ModuleFlags, style: NavStyle = NavStyle.TEXT_ONLY, shortcuts: Int = 0): Float = CANVAS - estimatedWidth(flags, style = style, shortcuts = shortcuts)

    /**
     * Horizontal padding of each nav pill. Normally the approved 23; only when the row would leave less than [MIN_SLACK] free does it shrink
     * (never below [MIN_ITEM_PADDING]). [extraWidth] lets tests simulate a wider row.
     */
    fun itemPadding(flags: ModuleFlags, extraWidth: Float = 0f, style: NavStyle = NavStyle.TEXT_ONLY, shortcuts: Int = 0): Float {
        val n = NavModel.items(flags).size.coerceAtLeast(1)
        val over = MIN_SLACK - (CANVAS - estimatedWidth(flags, extraWidth = extraWidth, style = style, shortcuts = shortcuts))
        return if (over <= 0f) ITEM_PADDING_H else (ITEM_PADDING_H - over / (2f * n)).coerceAtLeast(MIN_ITEM_PADDING)
    }
}
