package io.github.nexalloy.`2eno`.googleads

import io.github.nexalloy.PatcherLog
import app.twoeno.extension.googleads.ReactNativeAdsPatch
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Member

private const val RNGMA = "io.invertase.googlemobileads"
private const val READABLE_MAP = "com.facebook.react.bridge.ReadableMap"

/**
 * Blocks the ads of React Native apps using react-native-google-mobile-ads.
 * Every ad request is answered with a "no fill" error, so the app does not wait for the ad.
 */
val HideReactNativeAds = patch(
    name = "Hide ads",
    description = "Blocks banner, interstitial, rewarded, app open and native Google ads.",
) {
    fun method(className: String, name: String, vararg parameterTypes: String): Member? =
        runCatching {
            XposedHelpers.findMethodExact(className, classLoader, name, *parameterTypes)
        }.onFailure {
            PatcherLog.info { "$className.$name not found: $it" }
        }.getOrNull()

    fun block(method: Member?, block: (MethodHookParam) -> Unit): Int {
        method?.hookMethod(object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                block(param)
                return null
            }
        }) ?: return 0
        return 1
    }

    val blocked = block(
        method(
            "$RNGMA.ReactNativeGoogleMobileAdsBannerAdViewManager", "requestAd",
            "$RNGMA.common.ReactNativeAdView",
        )
    ) { ReactNativeAdsPatch.blockBannerAd(it.thisObject, it.args[0]) } + block(
        // Interstitial, rewarded and app open ads.
        method(
            "$RNGMA.ReactNativeGoogleMobileAdsFullScreenAdModule", "load",
            "int", "java.lang.String", READABLE_MAP,
        )
    ) { ReactNativeAdsPatch.blockFullScreenAd(it.thisObject, it.args[0] as Int, it.args[1] as String?) } + block(
        method(
            "$RNGMA.ReactNativeGoogleMobileAdsNativeModule", "load",
            "java.lang.String", READABLE_MAP, "com.facebook.react.bridge.Promise",
        )
    ) { ReactNativeAdsPatch.blockNativeAd(it.args[2]) } + block(
        // Ads loaded by native code instead of the React Native module.
        method("com.google.android.gms.ads.BaseAdView", "loadAd", "com.google.android.gms.ads.AdRequest")
    ) { }

    if (blocked == 0) throw Exception("Google ads code not found")
}
