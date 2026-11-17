package no.aura.os.v2

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class F6hCategoryFocusTest {
    private fun src(path: String) = File("src/main/java/no/aura/os/v2/$path").readText()

    @Test fun category_status_actions_are_sequential_and_bidirectionally_linked() {
        val host = src("overlay/CategoryCatalogHost.kt")
        val page = src("pages/ShelfPage.kt")
        assertTrue(page.contains("downTarget = statusEntry ?: afterStatus"))
        assertTrue(page.contains("up = statusExit ?: entry"))
        assertTrue(page.contains("categoryRequester else statusExit ?: entry"))
        assertTrue(host.contains("up = statusRequesters.getOrNull(index - 1) ?: up"))
        assertTrue(host.contains("down = statusRequesters.getOrNull(index + 1) ?: down"))
    }

    @Test fun late_category_rows_keep_state_by_feed_identity() {
        assertTrue(src("overlay/CategoryCatalogHost.kt").contains("shelfStateIndex = { CategoryFeed.valueOf(it).ordinal }"))
        val page = src("pages/ShelfPage.kt")
        assertTrue(page.contains("ui.rows[shelfStateIndex?.invoke(shelf.id) ?: i]"))
        assertTrue(page.contains("requestersById.getOrPut(it.id)"))
    }
}
