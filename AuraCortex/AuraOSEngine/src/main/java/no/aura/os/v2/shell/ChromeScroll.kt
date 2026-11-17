package no.aura.os.v2.shell

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState

/** Each shell route owns a fresh state; only its outer vertical page reports here. */
class ChromeScrollState { var scrolled by mutableStateOf(false) }
val LocalChromeScroll = staticCompositionLocalOf { ChromeScrollState() }
object ChromePolicy {
    fun isScrolled(firstItem: Int, offset: Int) = firstItem > 0 || offset > 0
    fun opacity(scrolled: Boolean) = if(scrolled) 1f else .8f
}
@Composable
fun ReportChromeScroll(list: LazyListState) {
    val chrome = LocalChromeScroll.current
    val scrolled by remember(list) { derivedStateOf { ChromePolicy.isScrolled(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
    LaunchedEffect(chrome, scrolled) { chrome.scrolled = scrolled }
}
