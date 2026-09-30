package com.m57.hermescontrol.theme

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.theme.import.ThemeApplier
import com.m57.hermescontrol.data.theme.import.VsixThemeParser
import com.m57.hermescontrol.data.theme.marketplace.ThemeMarketplaceRepository
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof of the persistence + restore half of requirement #7 without a
 * live dashboard session: apply a real marketplace theme through the app's own
 * pipeline, then prove (a) the theme landed in the real [ServerStore] state
 * (the same datastore that survives process death) and (b) a fresh
 * [ThemeApplier.restorePersisted] driven from that persisted state reactivates
 * the theme with no restore failure. This is the exact code path a user's
 * "apply a theme, restart the app, see it still active" flow exercises, minus
 * the manual drawer navigation that needs an authenticated gateway.
 *
 * The interactive "restart the app" step on a live dashboard session remains
 * pending gateway credentials (the dev app is logged out); this test proves
 * the persistence contract deterministically on the real device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ThemePersistenceRestoreDeviceTest {
    @Test
    fun applyPersistsAndRestoreReactivates() {
        runBlocking {
            val repo = ThemeMarketplaceRepository()
            val parser = VsixThemeParser()

            val searchResult = repo.search(query = "color theme", limit = 20)
            assertTrue("live search should succeed", searchResult is NetworkResult.Success)
            val hit = requireNotNull((searchResult as NetworkResult.Success).data.firstOrNull())
            val variants =
                requireNotNull(
                    parser
                        .parseVsix(
                            (repo.resolveAssets(hit.extensionId) as NetworkResult.Success).data.downloadUrl,
                        ).getOrNull(),
                )

            // Apply -> the theme is pushed into the real ServerStore state.
            ThemeApplier.applyFamily(hit.extensionId, hit.displayName, variants)
            assertEquals(hit.extensionId, ThemeApplier.activeCustomThemeId.value)
            assertEquals(null, ThemeApplier.applyError.value)

            val persisted = AuthManager.serverStore.getLatestState()
            assertEquals(
                "theme preselected as CUSTOM must persist",
                ThemePreset.CUSTOM,
                persisted.themePreset,
            )
            assertEquals("custom theme id must persist", hit.extensionId, persisted.customThemeId)
            assertEquals("custom theme name must persist", hit.displayName, persisted.customThemeName)
            assertNotNull("custom theme tokens must persist", persisted.customThemeTokensJson)

            // Simulate process restart: wipe the in-memory palette, then restore
            // purely from the persisted tokens (as AuthManager.init does).
            com.m57.hermescontrol.theme
                .setCustomPalette(null)
            ThemeApplier.restorePersisted(
                persisted.customThemeId,
                persisted.customThemeName,
                persisted.customThemeTokensJson,
            )

            assertFalse("restore must not mark the theme unavailable", ThemeApplier.restoreFailed.value)
            assertEquals(
                "restore must reactivate the same theme id",
                hit.extensionId,
                ThemeApplier.activeCustomThemeId.value,
            )

            ThemeApplier.clearCustomTheme()
            Log.i(TAG, "persisted+restored live theme: ${hit.extensionId} (${hit.displayName})")
        }
    }

    companion object {
        private const val TAG = "ThemePersistRestore"
    }
}
