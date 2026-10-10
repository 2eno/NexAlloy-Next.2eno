package io.github.nexalloy.piko.instagram.misc

import android.app.Activity
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.bloksFullScreenAppIdField
import io.github.nexalloy.piko.instagram.bloksFullScreenOpenMethod
import io.github.nexalloy.piko.instagram.buildAgeField
import io.github.nexalloy.piko.instagram.buildAgeInitMethod
import io.github.nexalloy.piko.instagram.developerOptionsClass
import io.github.nexalloy.piko.instagram.homeIconActivityField
import io.github.nexalloy.piko.instagram.homeIconOnLongClickMethod
import io.github.nexalloy.piko.instagram.homeIconUserSessionField
import io.github.nexalloy.piko.instagram.mainFeedRequestClass
import io.github.nexalloy.piko.instagram.mainFeedRequestHeadersField
import io.github.nexalloy.piko.instagram.shared.InterceptRequests
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.storiesAudioEnabledMethod
import io.github.nexalloy.piko.instagram.userAgentDisplayMetricsMethod
import io.github.nexalloy.piko.instagram.videoAutoplayDisabledMethod
import java.lang.reflect.Modifier

val DisableVideoAutoplay = patch(
    name = "Disable video autoplay",
    description = "Stops videos from playing automatically in the feed.",
    use = false,
) {
    ::videoAutoplayDisabledMethod.hookMethod(returnConstant(true))
}

val StoriesAudioAutoplay = patch(
    name = "Stories audio autoplay",
    description = "Plays the audio of stories automatically, even if the phone is muted.",
    use = false,
) {
    ::storiesAudioEnabledMethod.hookMethod(returnConstant(true))
}

private val onboardingScreens = setOf(
    "com.bloks.www.bloks.ig.ndx.ci.entry.screen", // Contacts import
    "com.bloks.www.bloks.ig.ndx.ls.entry.screen", // Location services
)

val DisableOnboardingPermissionPrompts = patch(
    name = "Disable onboarding permission prompts",
    description = "Prevents the contacts and location permission prompts from appearing.",
    use = false,
) {
    dependsOn(InterceptRequests)
    RequestFilter.enable(RequestFilter.Rule.ONBOARDING_CONSENT)

    val appId = ::bloksFullScreenAppIdField.field.apply { isAccessible = true }
    ::bloksFullScreenOpenMethod.hookMethod {
        before { param ->
            if (appId.get(param.thisObject) in onboardingScreens) param.result = null
        }
    }
}

val RemoveBuildExpiredPopup = patch(
    name = "Remove build expired popup",
    description = "Removes the popup that appears when the app version gets old.",
    use = false,
) {
    val appAge = ::buildAgeField.field.apply { isAccessible = true }
    ::buildAgeInitMethod.hookMethod {
        after { param ->
            val owner = param.thisObject ?: return@after
            // The app age in days.
            if (appAge.declaringClass.isInstance(owner)) appAge.setInt(owner, 1)
        }
    }
}

val LimitFeedToFollowingProfiles = patch(
    name = "Limit feed to following profiles",
    description = "Shows only posts of profiles you follow in the home feed.",
    use = false,
) {
    val headers = ::mainFeedRequestHeadersField.field.apply { isAccessible = true }
    XposedBridge.hookAllConstructors(::mainFeedRequestClass.clazz, object : de.robv.android.xposed.XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            @Suppress("UNCHECKED_CAST")
            val requestHeaders = headers.get(param.thisObject) as? Map<String, String> ?: return
            // Only change requests of the default feed.
            val source = requestHeaders["pagination_source"]
            if (source != null && source != "feed_recs") return
            headers.set(param.thisObject, requestHeaders + ("pagination_source" to "following"))
        }
    })
}

private const val MAX_IMAGE_SIZE = 4096

val ImproveImageViewing = patch(
    name = "Improve image viewing",
    description = "Requests images in the highest resolution available.",
    use = false,
) {
    // The user agent contains the display metrics, e.g. "420dpi; 1080x2400".
    val displayMetrics = Regex("""(\d+dpi; )\d+x\d+""")
    ::userAgentDisplayMetricsMethod.hookMethod {
        after { param ->
            val metrics = param.result as? String ?: return@after
            param.result = displayMetrics.replace(metrics) { "${it.groupValues[1]}${MAX_IMAGE_SIZE}x$MAX_IMAGE_SIZE" }
        }
    }
}

val UnlockDeveloperOptions = patch(
    name = "Unlock developer options",
    description = "Opens the developer options by long pressing the home button.",
    use = false,
) {
    val activityField = ::homeIconActivityField.field.apply { isAccessible = true }
    val userSessionField = ::homeIconUserSessionField.field.apply { isAccessible = true }

    val developerOptions = ::developerOptionsClass.clazz
    val instanceField = developerOptions.declaredFields.firstOrNull {
        Modifier.isStatic(it.modifiers) && it.type == developerOptions
    } ?: developerOptions.declaredFields.first { Modifier.isStatic(it.modifiers) }
    instanceField.isAccessible = true
    // open(Activity activity, Context context, UserSession userSession)
    fun Class<*>.relatedTo(other: Class<*>) = isAssignableFrom(other) || other.isAssignableFrom(this)
    val openMethod = developerOptions.declaredMethods.single {
        !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 3 &&
            it.parameterTypes[0].relatedTo(Activity::class.java) &&
            it.parameterTypes[2].relatedTo(userSessionField.type)
    }.apply { isAccessible = true }

    ::homeIconOnLongClickMethod.hookMethod {
        before { param ->
            val activity = activityField.get(param.thisObject) as? Activity ?: return@before
            val userSession = userSessionField.get(param.thisObject)
            try {
                openMethod.invoke(instanceField.get(null), activity, activity, userSession)
                param.result = true
            } catch (e: Exception) {
                Logger.printException({ "Could not open the developer options" }, e)
            }
        }
    }
}
