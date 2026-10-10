package io.github.nexalloy.piko

import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.AppTarget
import io.github.nexalloy.PatchBundle
import io.github.nexalloy.piko.instagram.InstagramPatches

/** Entry point of the Piko Patches bundle, named in its manifest. */
object PikoPatches : PatchBundle {
    override val apps = listOf(
        // The version Piko's Instagram patches are tested with, see crimera/piko.
        AppPatchInfo(
            "Instagram", "com.instagram.android", InstagramPatches,
            targets = listOf(AppTarget("447.0.0.55.81")),
            appIconColor = 0xFC483C,
        ),
    )
}
