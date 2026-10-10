package io.github.nexalloy.`2eno`

import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.PatchBundle
import io.github.nexalloy.`2eno`.interpals.InterPalsPatches
import io.github.nexalloy.`2eno`.kleinanzeigen.KleinanzeigenPatches
import io.github.nexalloy.`2eno`.spotify.SpotifyPatches
import io.github.nexalloy.`2eno`.untappd.UntappdPatches

/** Entry point of the 2eno Patches bundle, named in its manifest. */
object Patches2eno : PatchBundle {
    override val apps = listOf(
        AppPatchInfo("Spotify", "com.spotify.music", SpotifyPatches),
        AppPatchInfo("Kleinanzeigen", "com.ebay.kleinanzeigen", KleinanzeigenPatches),
        AppPatchInfo("Untappd", "com.untappdllc.app", UntappdPatches),
        AppPatchInfo("InterPals", "net.interpals", InterPalsPatches),
    )
}
