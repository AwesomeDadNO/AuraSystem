package no.aura.os.v2
import no.aura.os.v2.cards.FocusOutline
import org.junit.Assert.*
import org.junit.Test
class FocusOutlineTest {
    private val stroke = 2f
    @Test fun normal_size_gets_half_stroke_inset() { assertEquals(1f, FocusOutline.insetFor(300f, 170f, stroke)!!, 0f) }
    @Test fun zero_width_or_height_draws_nothing() { assertNull(FocusOutline.insetFor(0f, 100f, stroke)); assertNull(FocusOutline.insetFor(100f, 0f, stroke)); assertNull(FocusOutline.insetFor(0f, 0f, stroke)) }
    @Test fun smaller_than_or_equal_to_stroke_draws_nothing() { assertNull(FocusOutline.insetFor(1f, 100f, stroke)); assertNull(FocusOutline.insetFor(100f, 2f, stroke)); assertNull(FocusOutline.insetFor(2f, 2f, stroke)) }
    @Test fun just_above_stroke_is_valid_and_inset_scope_stays_positive() {
        val i = FocusOutline.insetFor(2.5f, 2.5f, stroke)!!; assertTrue(2.5f - 2 * i > 0f)
    }
    @Test fun non_finite_or_invalid_stroke_draws_nothing() {
        assertNull(FocusOutline.insetFor(Float.NaN, 100f, stroke)); assertNull(FocusOutline.insetFor(100f, Float.POSITIVE_INFINITY, stroke))
        assertNull(FocusOutline.insetFor(100f, 100f, 0f)); assertNull(FocusOutline.insetFor(100f, 100f, -1f)); assertNull(FocusOutline.insetFor(100f, 100f, Float.NaN))
    }
    @Test fun negative_sizes_draw_nothing() { assertNull(FocusOutline.insetFor(-5f, 100f, stroke)) }
}
