package io.github.nexalloy.`2eno`

import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.AppTarget
import io.github.nexalloy.PatchBundle
import io.github.nexalloy.`2eno`.interpals.InterPalsPatches
import io.github.nexalloy.`2eno`.kleinanzeigen.KleinanzeigenPatches
import io.github.nexalloy.`2eno`.spotify.SpotifyPatches
import io.github.nexalloy.`2eno`.untappd.UntappdPatches

/** Entry point of the 2eno Patches bundle, named in its manifest. */
object Patches2eno : PatchBundle {
    override val apps = listOf(
        // Targets are the versions the patches were last tested with.
        AppPatchInfo(
            "Spotify", "com.spotify.music", SpotifyPatches,
            targets = listOf(AppTarget("9.1.88.2204")), appIconColor = 0x1ED760,
        ),
        AppPatchInfo(
            "Kleinanzeigen", "com.ebay.kleinanzeigen", KleinanzeigenPatches,
            targets = listOf(AppTarget("2026.41.0")), appIconColor = 0x86B817,
        ),
        AppPatchInfo("Untappd", "com.untappdllc.app", UntappdPatches, appIconColor = 0xFFC000),
        AppPatchInfo(
            "InterPals", "net.interpals", InterPalsPatches,
            targets = listOf(AppTarget("2.16.1")), appIconColor = 0x3B6EA5,
        ),
    )
}
