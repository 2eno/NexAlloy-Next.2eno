package io.github.nexalloy.`2eno`.spotify.ads

import android.app.Activity
import android.os.Bundle
import android.view.View
import app.twoeno.extension.spotify.HideAdViewsPatch
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val HideAdViews = patch(
    name = "Hide ad views",
    description = "Hides ad banners, display ads and the ad player.",
) {
    HideAdViewsPatch.AD_VIEW_CLASSES.forEach { className ->
        val adView = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        XposedHelpers.findMethodExactIfExists(adView, "onMeasure", Int::class.java, Int::class.java)?.hookMethod {
            before { param ->
                HideAdViewsPatch.hideView(param.thisObject as View)
                param.result = null
            }
        }
        XposedHelpers.findMethodExactIfExists(adView, "onAttachedToWindow")?.hookMethod {
            after { param -> HideAdViewsPatch.hideView(param.thisObject as View) }
        }
    }

    // Ad views not covered by the known classes are found by their class, resource and content description names.
    Activity::class.java.getDeclaredMethod("onPostResume").hookMethod {
        after { param ->
            (param.thisObject as Activity).window?.decorView?.let(HideAdViewsPatch::hideAdViews)
        }
    }
    Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java).hookMethod {
        after { param ->
            val decorView = (param.thisObject as Activity).window?.decorView ?: return@after
            decorView.post { HideAdViewsPatch.hideAdViews(decorView) }
        }
    }
}
