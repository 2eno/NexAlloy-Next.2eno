package io.github.nexalloy.patches

import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.AppTarget
import io.github.nexalloy.Patch
import io.github.nexalloy.PatchBundle
import io.github.nexalloy.hoodles.morphe.alltrails.AllTrailsPatches
import io.github.nexalloy.morphe.music.YTMusicPatches
import io.github.nexalloy.morphe.reddit.RedditPatches
import io.github.nexalloy.morphe.youtube.YouTubePatches
import io.github.nexalloy.revanced.googlephotos.GooglePhotosPatches
import io.github.nexalloy.revanced.meta.MetaPatches
import io.github.nexalloy.revanced.photomath.PhotomathPatches
import io.github.nexalloy.revanced.strava.StravaPatches

/** Entry point of the NexAlloy Patches bundle, named in its manifest. */
object NexAlloyPatches : PatchBundle {
    private fun app(appName: String, packageName: String, patches: Array<Patch>, targets: List<AppTarget> = emptyList()) =
        AppPatchInfo(appName, packageName, arrayOf(ExtensionContextHook, *patches), targets)

    override val apps = listOf(
        app("YouTube", "com.google.android.youtube", YouTubePatches, MorpheTargets.YOUTUBE),
        app("YT Music", "com.google.android.apps.youtube.music", YTMusicPatches, MorpheTargets.MUSIC),
        app("Reddit", "com.reddit.frontpage", RedditPatches, MorpheTargets.REDDIT),
        app("Google Photos", "com.google.android.apps.photos", GooglePhotosPatches),
        app("Photomath", "com.microblink.photomath", PhotomathPatches),
        app("Threads", "com.instagram.barcelona", MetaPatches),
        app("Strava", "com.strava", StravaPatches),
        app("AllTrails", "com.alltrails.alltrails", AllTrailsPatches),
    )
}
