package io.github.nexalloy.patches

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.ResourceUtils
import app.morphe.extension.shared.Utils
import io.github.nexalloy.addModuleAssets
import io.github.nexalloy.callMethod
import io.github.nexalloy.patch

/** Gives the Morphe extension code the app context. Must run before any other patch of this bundle. */
val ExtensionContextHook = patch {
    Utils.setContext(appContext)
}

/** Makes the bundle's resources available to the Morphe extension code in every activity. */
val ExtensionResourceHook = patch {
    appContext.addModuleAssets()
    ResourceUtils.fallbackPackageName = BuildConfig.APPLICATION_ID

    appContext.callMethod(
        "registerActivityLifecycleCallbacks", object : Application.ActivityLifecycleCallbacks {
            var handleWebView: Boolean = false

            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                Logger.printDebug { "onActivityCreated $activity" }
                if (!handleWebView) {
                    WebView(activity).destroy()
                    appContext.addModuleAssets()
                    handleWebView = true
                }

                activity.addModuleAssets()
            }

            override fun onActivityDestroyed(activity: Activity) {
                Logger.printDebug { "onActivityDestroyed $activity" }
            }

            override fun onActivityPaused(activity: Activity) {
                Logger.printDebug { "onActivityPaused $activity" }
            }

            override fun onActivityResumed(activity: Activity) {
                Logger.printDebug { "onActivityResumed $activity" }
                activity.addModuleAssets()
            }

            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
                Logger.printDebug { "onActivitySaveInstanceState $activity" }
            }

            override fun onActivityStarted(activity: Activity) {
                Logger.printDebug { "onActivityStarted $activity" }
            }

            override fun onActivityStopped(activity: Activity) {
                Logger.printDebug { "onActivityStopped $activity" }
            }
        })
}
