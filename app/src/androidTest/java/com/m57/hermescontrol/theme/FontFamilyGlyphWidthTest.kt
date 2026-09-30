package com.m57.hermescontrol.theme

import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Spec F2/F3: prove the font options actually render at different widths in
 * the app's OWN Compose pipeline (t_f3c6f528). Each option is set as the
 * material `Typography.fontFamily` via [HermesControlTheme], a fixed
 * discriminating sample is rendered, and the measured on-screen width is
 * logged as CSV and asserted distinct for families that are distinct by
 * construction.
 *
 * Unlike a raw `android.graphics` `Paint.measureText` probe, this exercises
 * exactly the path the app uses — `Theme.kt` builds a Material `Typography`
 * whose every style carries the selected [FontFamily], and Compose lays out
 * real on-screen text. The proof is therefore about what the user actually
 * sees, not an off-graphics probe.
 *
 * Two collision classes are handled deliberately:
 * - **Hard failure** for [DISTINCT_BY_CONSTRUCTION]: Sans Serif, Serif and
 *   Monospace are separate platform families, so two of them at the same
 *   width is a real regression.
 * - **Reported, not failed** for the rest. `SYSTEM` is expected to alias the
 *   platform default (sans-serif) and `CURSIVE` depends on the image shipping
 *   the family; both are logged with their numbers for a data-driven
 *   merge-or-label decision.
 */
@RunWith(AndroidJUnit4::class)
class FontFamilyGlyphWidthTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun everyFontOptionRendersDistinctGlyphs() {
        composeTestRule.setContent {
            Column {
                AppFontFamily.entries.forEach { family ->
                    // Every line uses the app's typography hierarchy and only
                    // differs by the family selection (same span count, same
                    // length); measured width is the objective signal.
                    Text(
                        text = SAMPLE,
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = family.toFontFamily,
                            ),
                        modifier = Modifier.testTag("glyph_${family.key}"),
                    )
                }
                // Candidate real-monospace family names, so we can choose a
                // genuinely distinct monospace even where the generic
                // "monospace" resolves to the platform default.
                val candidates =
                    mapOf(
                        "courier" to FontFamily(Typeface.create("courier", Typeface.NORMAL)),
                        "sans-serif-monospace" to FontFamily(Typeface.create("sans-serif-monospace", Typeface.NORMAL)),
                        "serif-monospace" to FontFamily(Typeface.create("serif-monospace", Typeface.NORMAL)),
                    )
                candidates.forEach { (name, ff) ->
                    Text(
                        text = SAMPLE,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ff),
                        modifier = Modifier.testTag("glyph_cand_$name"),
                    )
                }
            }
        }

        val widths: MutableMap<AppFontFamily, Float> =
            AppFontFamily
                .entries
                .associateWith { family ->
                    val bounds =
                        composeTestRule
                            .onNodeWithTag("glyph_${family.key}", useUnmergedTree = true)
                            .getUnclippedBoundsInRoot()
                    (bounds.right - bounds.left).value
                }.toMutableMap()

        // Candidate labels -> widths, appended to the CSV for the decision.
        val candidateWidths =
            listOf("courier", "sans-serif-monospace", "serif-monospace").associateWith { name ->
                val bounds =
                    composeTestRule
                        .onNodeWithTag("glyph_cand_$name", useUnmergedTree = true)
                        .getUnclippedBoundsInRoot()
                (bounds.right - bounds.left).value
            }
        Log.i(TAG, "mono-candidates: $candidateWidths")

        val csv = toCsv(widths)
        Log.i(TAG, "font-glyph-widths.csv:\n$csv")

        val collisions =
            widths.entries.flatMap { (a, widthA) ->
                widths.entries
                    .filter { it.key.key > a.key }
                    .filter { (_, widthB) -> withinTolerance(widthA, widthB) }
                    .map { (b, widthB) -> Collision(a, b, widthA, widthB) }
            }

        val mustBeDistinct = collisions.filter { it.bothIn(DISTINCT_BY_CONSTRUCTION) }
        val needsProductDecision = collisions.filterNot { it.bothIn(DISTINCT_BY_CONSTRUCTION) }

        if (needsProductDecision.isNotEmpty()) {
            Log.w(
                TAG,
                "Options that render at the same width but are not aliases by construction. " +
                    "Per spec F2, merge them or label the pair as an alias: " +
                    needsProductDecision.joinToString("; ") { it.describe() } +
                    "\n$csv",
            )
        }

        assertTrue(
            "Families distinct by construction rendered at the same on-screen width " +
                "(within ${TOLERANCE * 100}%): ${mustBeDistinct.joinToString("; ") { it.describe() }}. " +
                "A real family is being shadowed by a fallback in the Compose pipeline.\n$csv",
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

    private fun toCsv(widths: Map<AppFontFamily, Float>): String =
        buildString {
            append("key,display_name,glyph_width_px\n")
            widths.forEach { (family, width) ->
                append("${family.key},${family.displayName},$width\n")
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

    companion object {
        private const val TAG = "FontGlyphWidthProof"
        private const val SAMPLE = "Il1O0 hermes 0123"
        private const val TOLERANCE = 0.01f

        /** Families the platform ships as genuinely separate faces. */
        private val DISTINCT_BY_CONSTRUCTION =
            listOf(AppFontFamily.SANS_SERIF, AppFontFamily.SERIF, AppFontFamily.MONOSPACE)
    }
}
