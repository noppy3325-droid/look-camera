package jp.noppy.lookcamera

import org.junit.Assert.*
import org.junit.Test

class LooksTest {
    @Test fun everyLookHasAStableIdAndItsOwnGrade() {
        val graded = Looks.all.filter { it.category != null }
        assertEquals(18, graded.size)
        assertEquals(19, Looks.all.map { it.id }.distinct().size)
        assertEquals(18, graded.map { it.grade }.distinct().size)
        assertEquals(3, graded.groupBy { it.category }.size)
    }

    @Test fun representativeLooksDifferOnAColorChart() {
        val neutral = Looks.byId("camera_natural_day").grade.map(0.5f, 0.5f, 0.5f)
        val golden = Looks.byId("film_golden_negative").grade.map(0.5f, 0.5f, 0.5f)
        assertTrue(golden[0] - golden[2] > neutral[0] - neutral[2] + 0.05f)
        val silver = Looks.byId("film_silver_grain").grade.map(0.8f, 0.3f, 0.2f)
        assertEquals(silver[0], silver[1], 0.001f)
        assertEquals(silver[0], silver[2], 0.001f)
    }

    @Test fun disabledIntensityAndUnknownLookFallBackToOriginal() {
        assertEquals(0f, LookSelection(Looks.byId("creative_ccd_flash"), -0.2f).amount, 0f)
        assertEquals(0f, LookSelection(Looks.byId("missing"), 1f).amount, 0f)
    }

    @Test fun capturedLookMetadataRoundTripsAndOldImagesRemainReadable() {
        val capture = LookSelection(Looks.byId("film_golden_negative"), 0.42f)
        assertEquals(capture, LookSelection.fromMetadata(capture.metadata))
        assertEquals(Looks.off, LookSelection.fromMetadata(null).preset)
        assertEquals(Looks.off, LookSelection.fromMetadata("unrelated camera text").preset)
    }
}

