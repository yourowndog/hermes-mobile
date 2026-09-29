package com.m57.hermescontrol.theme

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Spec F2/F3: prove the font options actually render differently, instead of
 * asserting that two `FontFamily` objects are unequal — which is always true
 * and says nothing about the glyphs Android draws.
 *
 * Measures real advance widths with [Paint.measureText] on a discriminating
 * sample (`Il1O0` catches 1/l/I and 0/O confusion that a plain word hides) and
 * emits the whole table as CSV, which is the machine-readable artifact F2
 * requires on the PR.
 *
 * Two collision classes are handled differently on purpose:
 *
 * - **Hard failure** for pairs inside [DISTINCT_BY_CONSTRUCTION]. Sans Serif,
 *   Serif and Monospace are separate real families in every AOSP image, so two
 *   of them measuring identically is a genuine regression and fails the build.
 * - **Reported, not failed** for every other pair. `SYSTEM` is expected to alias
 *   `SANS_SERIF`: Compose resolves `FontFamily.Default` to the platform default,
 *   which *is* the sans-serif family on Android, so identical widths there are
 *   correct behaviour rather than a defect. `CURSIVE` depends on the emulator
 *   image actually shipping the family. Both are logged with their measured
 *   numbers so the merge-or-label decision F2 asks for can be made from data
 *   instead of assumption.
 *
 * Runs on the CI API 34 ATD job — deliberately not a JVM unit test, because
 * `android.graphics` text measurement means nothing off-device and this module
 * has no Robolectric dependency.
 */
@RunWith(AndroidJUnit4::class)
class FontFamilyGlyphWidthTest {
    @Test
    fun everyFontOptionRendersDistinctGlyphs() {
        val paint =
            Paint().apply {
                textSize = TEXT_SIZE_PX
                isAntiAlias = false
            }

        val measurements =
            AppFontFamily.entries.associateWith { family ->
                paint.typeface = platformTypeface(family)
                // Warm the glyph cache so the first sample is not penalised.
                paint.measureText(SAMPLE)
                paint.measureText(SAMPLE)
            }

        val csv = toCsv(measurements)
        Log.i(TAG, "font-glyph-widths.csv:\n$csv")
        writeArtifact("font-glyph-widths.csv", csv)

        val collisions =
            measurements.entries.flatMap { (a, widthA) ->
                measurements.entries
                    .filter { it.key.key > a.key.key }
                    .filter { (_, widthB) -> withinTolerance(widthA, widthB) }
                    .map { (b, widthB) -> Collision(a, b, widthA, widthB) }
            }

        val mustBeDistinct = collisions.filter { it.bothIn(DISTINCT_BY_CONSTRUCTION) }
        val needsProductDecision = collisions.filterNot { it.bothIn(DISTINCT_BY_CONSTRUCTION) }

        if (needsProductDecision.isNotEmpty()) {
            Log.w(
                TAG,
                "Options that measure identically but are not aliases by construction. " +
                    "Per spec F2, merge them or label the label as an alias: " +
                    needsProductDecision.joinToString("; ") { it.describe() } +
                    "\n$csv",
            )
        }

        assertTrue(
            "Families that are distinct by construction measured identically (within " +
                "${TOLERANCE * 100}%): ${mustBeDistinct.joinToString("; ") { it.describe() }}. " +
                "A real family is being shadowed by a fallback on this image.\n$csv",
            mustBeDistinct.isEmpty(),
        )
    }

    private data class Collision(
        val a: AppFontFamily,
        val b: AppFontFamily,
        val widthA: Float,
        val widthB: Float,
    ) {
        fun bothIn(subset: List<AppFontFamily>): Boolean = a in subset && b in subset

        fun describe(): String = "${a.displayName} (${widthA}px) vs ${b.displayName} (${widthB}px)"
    }

    private fun toCsv(measurements: Map<AppFontFamily, Float>): String =
        buildString {
            append("key,display_name,platform_typeface,glyph_width_px\n")
            measurements.forEach { (family, width) ->
                append("${family.key},${family.displayName},${family.platformTypefaceName},$width\n")
            }
        }

    private fun withinTolerance(
        a: Float,
        b: Float,
    ): Boolean {
        val widest = maxOf(a, b)
        if (widest <= 0f) return true
        return abs(a - b) / widest <= TOLERANCE
    }

    private fun writeArtifact(
        name: String,
        contents: String,
    ) {
        // Best effort: CI captures the Log.i above as the durable artifact, since
        // an emulator cacheDir is not retrievable from the job afterwards.
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.cacheDir.resolve(name).writeText(contents)
        }
    }

    companion object {
        private const val TAG = "FontGlyphWidthProof"
        private const val SAMPLE = "Il1O0 hermes 0123"
        private const val TEXT_SIZE_PX = 48f
        private const val TOLERANCE = 0.01f

        /**
         * Families Android ships as genuinely separate faces. A collision inside
         * this set means one of them resolved to a fallback, so it fails.
         */
        private val DISTINCT_BY_CONSTRUCTION =
            listOf(AppFontFamily.SANS_SERIF, AppFontFamily.SERIF, AppFontFamily.MONOSPACE)

        /**
         * Map the Compose [androidx.compose.ui.text.font.FontFamily] options onto
         * the platform families Android actually resolves them to. Compose maps
         * `FontFamily.Default`/`SansSerif` to the sans-serif family, which is
         * exactly the aliasing this test is meant to make visible.
         */
        private fun platformTypeface(family: AppFontFamily): Typeface =
            when (family) {
                AppFontFamily.SYSTEM -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                AppFontFamily.SANS_SERIF -> Typeface.create("sans-serif", Typeface.NORMAL)
                AppFontFamily.SERIF -> Typeface.create("serif", Typeface.NORMAL)
                AppFontFamily.MONOSPACE -> Typeface.create("monospace", Typeface.NORMAL)
                AppFontFamily.CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
            }

        private val AppFontFamily.platformTypefaceName: String
            get() =
                when (this) {
                    AppFontFamily.SYSTEM -> "default"
                    AppFontFamily.SANS_SERIF -> "sans-serif"
                    AppFontFamily.SERIF -> "serif"
                    AppFontFamily.MONOSPACE -> "monospace"
                    AppFontFamily.CURSIVE -> "cursive"
                }
    }
}
