package com.m57.hermescontrol.theme

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.theme.import.ThemeApplier
import com.m57.hermescontrol.data.theme.import.VsixThemeParser
import com.m57.hermescontrol.data.theme.marketplace.ThemeMarketplaceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof of the marketplace apply pipeline against LIVE sources
 * (t_f3c6f528 / t_5316ccb7), on-device: search the real VS Code Gallery,
 * resolve a downloadable `.vsix`, download + parse it, apply it, and confirm
 * the applied theme is active and that [ThemeApplier] reports no conversion
 * error. This exercises the exact code paths a user taps in the Marketplace
 * screen — no mock, no fake server.
 *
 * It needs network, so it is `@LargeTest` and only meaningful on a connected
 * device/emulator. It pins nothing about *which* theme wins (gallery sort
 * order can shift); it asserts the pipeline produces a usable, applied theme
 * from a real package. The generic "monospace" family aliasing that this task
 * fixes is covered separately by [FontFamilyGlyphWidthTest].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MarketplaceLiveApplyTest {
    @Test
    fun applyFirstSearchHit_endToEnd() {
        runBlocking {
            val repo = ThemeMarketplaceRepository()
            val parser = VsixThemeParser()

            // 1. Search the live catalog, pick the first non-icon theme.
            val searchResult = repo.search(query = "color theme", limit = 20)
            assertTrue("live search should succeed", searchResult is NetworkResult.Success)
            val hit =
                requireNotNull((searchResult as NetworkResult.Success).data.firstOrNull()) {
                    "live gallery returned no usable theme"
                }

            // 2. Resolve a downloadable package.
            val assets = repo.resolveAssets(hit.extensionId)
            assertTrue("resolveAssets should succeed", assets is NetworkResult.Success)
            val vsixUrl = (assets as NetworkResult.Success).data.downloadUrl
            assertTrue("vsix url should be https", vsixUrl.startsWith("https://"))

            // 3. Download + parse every contributed variant.
            val variants = parser.parseVsix(vsixUrl).getOrNull()
            requireNotNull(variants) { "vsix should parse into variants" }
            assertFalse("parsed variants must contain colors", variants.all { it.colors.isEmpty() })

            // 4. Apply through the same code the Marketplace screen calls.
            ThemeApplier.applyFamily(hit.extensionId, hit.displayName, variants)
            assertTrue(
                "apply should not report a conversion error but reported: ${ThemeApplier.applyError.value}",
                ThemeApplier.applyError.value == null,
            )
            assertTrue(
                "extension should be active after apply",
                ThemeApplier.activeCustomThemeId.value == hit.extensionId,
            )

            // 5. Clean up so repeated runs start from a neutral state.
            ThemeApplier.clearCustomTheme()
            Log.i(TAG, "applied live theme: ${hit.extensionId} (${hit.displayName})")
        }
    }

    companion object {
        private const val TAG = "MarketplaceLiveApply"
    }
}
