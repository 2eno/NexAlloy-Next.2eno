package io.github.nexalloy.piko

import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.PatchBundle
import io.github.nexalloy.piko.instagram.InstagramPatches

/** Entry point of the Piko Patches bundle, named in its manifest. */
object PikoPatches : PatchBundle {
    override val apps = listOf(
        AppPatchInfo("Instagram", "com.instagram.android", InstagramPatches),
    )
}
