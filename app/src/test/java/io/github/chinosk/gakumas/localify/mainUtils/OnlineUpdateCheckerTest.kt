package io.github.chinosk.gakumas.localify.mainUtils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineUpdateCheckerTest {
    private fun gameRelease(version: String, publishedAt: String = ""): ReleaseInfo {
        return ReleaseInfo(
            tag_name = "game-v$version",
            published_at = publishedAt,
            assets = listOf(
                ReleaseAssetInfo(
                    name = "gkms-game-patch.json",
                    browser_download_url = "https://example.invalid/$version.json"
                )
            )
        )
    }

    @Test
    fun selectsHighestGameVersionRegardlessOfApiOrder() {
        val releases = listOf(
            gameRelease("3.2.0"),
            gameRelease("3.1.2"),
            gameRelease("3.2.1")
        )

        val latest = findLatestRelease(releases, "game-v") {
            it.name == "gkms-game-patch.json"
        }

        assertEquals("game-v3.2.1", latest?.tag_name)
    }

    @Test
    fun usesPublishTimeToBreakSameVersionTies() {
        val older = gameRelease("3.2.1", "2026-07-21T00:00:00Z")
        val newer = gameRelease("3.2.1", "2026-07-22T00:00:00Z")

        val latest = findLatestRelease(listOf(newer, older), "game-v") {
            it.name == "gkms-game-patch.json"
        }

        assertEquals(newer, latest)
    }

    @Test
    fun neverOffersAnOlderGameVersion() {
        assertFalse(
            shouldUpdateGamePatch(
                installedVersion = "3.2.1",
                installedIsPatched = true,
                installedPatchMode = "lspatch-embedded",
                targetVersion = "3.2.0",
                targetPatchMode = "lspatch-embedded"
            )
        )
    }

    @Test
    fun sameVersionOnlyUpdatesWhenPatchStateNeedsRepair() {
        assertFalse(
            shouldUpdateGamePatch(
                installedVersion = "3.2.1",
                installedIsPatched = true,
                installedPatchMode = "lspatch-embedded",
                targetVersion = "3.2.1",
                targetPatchMode = "lspatch-embedded"
            )
        )
        assertTrue(
            shouldUpdateGamePatch(
                installedVersion = "3.2.1",
                installedIsPatched = false,
                installedPatchMode = null,
                targetVersion = "3.2.1",
                targetPatchMode = "lspatch-embedded"
            )
        )
    }

    @Test
    fun offersNewerGameVersion() {
        assertTrue(
            shouldUpdateGamePatch(
                installedVersion = "3.2.0",
                installedIsPatched = true,
                installedPatchMode = "lspatch-embedded",
                targetVersion = "3.2.1",
                targetPatchMode = "lspatch-embedded"
            )
        )
    }
}
