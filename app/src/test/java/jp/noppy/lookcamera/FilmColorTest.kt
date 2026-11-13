package jp.noppy.lookcamera

import org.junit.Assert.*
import org.junit.Test

class FilmColorTest {
    private val golden = Looks.byId("film_golden_negative").grade

    @Test fun daylightKeepsWhitePaintCreamInsteadOfOrange() {
        val white = golden.map(.95f, .95f, .95f)
        val middle = golden.map(.55f, .55f, .55f)
        assertTrue(white.min() >= .86f)
        assertTrue(white.max() - white.min() < .035f)
        assertTrue(middle[0] - middle[2] in .055f.. .145f)
    }

    @Test fun daylightPreservesDetailAtBothEndsOfTheCurve() {
        val shadow = golden.map(.07f, .07f, .07f)
        val nextShadow = golden.map(.12f, .12f, .12f)
        val white = golden.map(.96f, .96f, .96f)
        val nextWhite = golden.map(.99f, .99f, .99f)
        assertTrue(nextShadow[1] - shadow[1] > .025f)
        assertTrue(nextWhite[1] - white[1] > .009f)
        assertTrue(shadow[1] > shadow[0])
    }

    @Test fun strongLooksKeepWhiteRampMonotonicAndSaturatedColorsInsideGamut() {
        for (look in Looks.all) {
            var previous = -1f
            for (step in 0..100) {
                val gray = step / 100f
                val graded = look.grade.map(gray, gray, gray)
                val luminance = graded[0] * .2126f + graded[1] * .7152f + graded[2] * .0722f
                assertTrue(look.name, luminance > previous)
                previous = luminance
            }
            val amber = look.grade.map(.99f, .46f, .07f)
            assertTrue(look.name, amber.all { it in 0f..1f })
        }
    }

    @Test fun vividSlideDoesNotPushAlreadySaturatedWarmColorsFurther() {
        val vivid = Looks.byId("film_vivid_slide").grade
        val strong = vivid.map(.91f, .38f, .13f)
        assertTrue(strong.max() - strong.min() <= (.91f - .13f) + .0001f)
        assertTrue(strong[0] > strong[1] && strong[1] > strong[2])
    }

    @Test fun neutralGradeHasNoHiddenColorCompression() {
        val original = floatArrayOf(.90f, .70f, .25f)
        val mapped = Grade().map(original[0], original[1], original[2])
        assertArrayEquals(original, mapped, .0002f)
    }
}

