package io.github.nexalloy.`2eno`.interpals.ads

import android.content.Context
import app.twoeno.extension.interpals.DisableAdPlacementsPatch
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val JS_BUNDLE_LOADER_COMPANION = "com.facebook.react.bridge.JSBundleLoader\$Companion"

val DisableAdPlacements = patch(
    name = "Disable ad placements",
    description = "Turns off all ad placements of the app, also the ones shown before the ad config is loaded.",
) {
    // The ad config downloaded from the server.
    XposedHelpers.findMethodExact(
        "okhttp3.internal.http.RealInterceptorChain", classLoader, "proceed", "okhttp3.Request",
    ).hookMethod {
        before { it.args[0] = DisableAdPlacementsPatch.filterRequest(it.args[0]) }
        after { it.result = DisableAdPlacementsPatch.filterResponse(it.result) }
    }

    // The defaults compiled into the JavaScript bundle: load a patched copy of the bundle.
    val createFileLoader = XposedHelpers.findMethodExact(
        JS_BUNDLE_LOADER_COMPANION, classLoader, "createFileLoader",
        String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
    )
    XposedHelpers.findMethodExact(
        JS_BUNDLE_LOADER_COMPANION, classLoader, "createAssetLoader",
        Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
    ).hookMethod {
        before { param ->
            val assetUrl = param.args[1] as? String ?: return@before
            val path = DisableAdPlacementsPatch.patchedBundlePath(param.args[0] as? Context, assetUrl)
                ?: return@before
            param.result = createFileLoader.invoke(param.thisObject, path, assetUrl, param.args[2])
        }
    }
}
