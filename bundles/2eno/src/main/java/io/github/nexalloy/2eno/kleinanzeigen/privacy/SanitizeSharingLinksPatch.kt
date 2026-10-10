package io.github.nexalloy.`2eno`.kleinanzeigen.privacy

import android.content.ClipData
import android.content.Intent
import app.twoeno.extension.kleinanzeigen.SanitizeSharingLinksPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val SOCIAL_SHARE_UTILS = "ebk.util.SocialShareUtils"

val SanitizeSharingLinks = patch(
    name = "Sanitize sharing links",
    description = "Removes the tracking parameters (utm_*) from shared listing and profile links.",
) {
    // Newer versions are obfuscated.
    ::sharingUrlBuilderMethods.dexMethodList.forEach {
        it.hookMethod {
            after { param -> (param.result as? String)?.let { url -> param.result = SanitizeSharingLinksPatch.sanitize(url) } }
        }
    }

    XposedHelpers.findClassIfExists(SOCIAL_SHARE_UTILS, classLoader)?.let { shareUtils ->
        // Returns the plain listing url instead of adding the tracking parameters.
        XposedBridge.hookAllMethods(shareUtils, "buildSharingUrl", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                val url = param.args.firstOrNull()
                return if (url is String) SanitizeSharingLinksPatch.sanitize(url) else url
            }
        })
        XposedBridge.hookAllMethods(shareUtils, "buildSharingProfileUrl", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.result as? String)?.let { param.result = SanitizeSharingLinksPatch.sanitize(it) }
            }
        })
    }

    // Safety net for links shared or copied from elsewhere in the app.
    val sanitizeText = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val text = param.args[1] as? CharSequence ?: return
            if (param.method.name == "putExtra" && param.args[0] != Intent.EXTRA_TEXT) return
            param.args[1] = SanitizeSharingLinksPatch.sanitize(text)
        }
    }
    Intent::class.java.getMethod("putExtra", String::class.java, String::class.java).hookMethod(sanitizeText)
    Intent::class.java.getMethod("putExtra", String::class.java, CharSequence::class.java).hookMethod(sanitizeText)
    ClipData::class.java.getMethod("newPlainText", CharSequence::class.java, CharSequence::class.java)
        .hookMethod(sanitizeText)
}
