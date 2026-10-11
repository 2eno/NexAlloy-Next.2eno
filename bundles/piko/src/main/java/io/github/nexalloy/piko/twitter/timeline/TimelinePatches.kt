package io.github.nexalloy.piko.twitter.timeline

import de.robv.android.xposed.XC_MethodReplacement.DO_NOTHING
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.piko.twitter.liveThreadsField
import io.github.nexalloy.piko.twitter.liveThreadsMethod
import io.github.nexalloy.piko.twitter.shared.FeatureFlags
import io.github.nexalloy.piko.twitter.shared.HookFeatureFlags
import io.github.nexalloy.piko.twitter.shared.hookJsonModel
import io.github.nexalloy.piko.twitter.shared.instanceFields
import io.github.nexalloy.piko.twitter.shared.jsonModel
import io.github.nexalloy.piko.twitter.socialProofDataMethod

val ShowSensitiveMedia = patch(
    name = "Show sensitive media",
    description = "Shows sensitive media without the content warning.",
) {
    val applied = listOf(
        "Sensitive media warnings" to {
            hookJsonModel(jsonModel("core.JsonSensitiveMediaWarning")) { warning ->
                instanceFields(warning.javaClass).filter { it.type == Boolean::class.javaPrimitiveType }
                    .forEach { it.setBoolean(warning, false) }
                warning
            }
        },
        "Blurred image interstitials" to {
            hookJsonModel(jsonModel("mediavisibility.JsonBlurredImageInterstitial")) { interstitial ->
                val fields = instanceFields(interstitial.javaClass)
                // The interstitial action and the verification options.
                fields.firstOrNull { it.type == String::class.java }?.set(interstitial, "")
                fields.firstOrNull { it.type == Any::class.java }?.set(interstitial, emptyList<Any>())
                interstitial
            }
        },
        "Compose interstitials" to {
            classLoader.loadClass("com.x.models.interstitial.MediaVisibilityResults")
                .getDeclaredMethod("getBlurImageInterstitial")
                .hookMethod(returnConstant(null))
        },
    ).count { (name, hook) ->
        runCatching(hook).onFailure { PatcherLog.info(it) { "$name are not shown" } }.isSuccess
    }
    if (applied == 0) throw Exception("No sensitive media implementation found")
}

val HidePromoteButton = patch(
    name = "Hide promote button",
    description = "Hides the promote button under your own posts.",
) {
    val eligibility = classLoader.loadClass(jsonModel("core.JsonTweetQuickPromoteEligibility"))
    hookJsonModel("com.twitter.api.model.json.core.JsonApiTweet") { tweet ->
        instanceFields(tweet.javaClass).filter { it.type == eligibility }.forEach { it.set(tweet, null) }
        tweet
    }
}

val HideLiveThreads = patch(
    name = "Hide live threads",
    description = "Hides the live threads above the home timeline.",
    use = false,
) {
    val threads = ::liveThreadsField.field.apply { isAccessible = true }
    require(List::class.java.isAssignableFrom(threads.type)) { "Unexpected live threads field $threads" }
    ::liveThreadsMethod.hookMethod {
        before { param ->
            if (threads.declaringClass.isInstance(param.thisObject)) threads.set(param.thisObject, null)
        }
    }
}

val RemoveViewCount = patch(
    name = "Remove view count",
    description = "Removes the view count below posts.",
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("view_counts_public_visibility_enabled" to false)
}

val HideBookmarkIcon = patch(
    name = "Hide bookmark icon in timeline",
    description = "Hides the bookmark button below posts in the timeline.",
    use = false,
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("bookmarks_in_timelines_enabled" to false)
}

val HideImmersivePlayer = patch(
    name = "Hide immersive player",
    description = "Removes swiping up for more videos in the video player.",
    use = false,
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("explore_relaunch_enable_immersive_player_across_twitter" to false)
}

val HideFollowedByContext = patch(
    name = "Hide followed by context",
    description = "Hides the \"Followed by\" context on profiles.",
    use = false,
) {
    ::socialProofDataMethod.hookMethod(DO_NOTHING)
}
