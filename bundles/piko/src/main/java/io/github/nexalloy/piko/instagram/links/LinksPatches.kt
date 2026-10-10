package io.github.nexalloy.piko.instagram.links

import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.FindFieldFunc
import io.github.nexalloy.FindMethodFunc
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.audioShareUrlField
import io.github.nexalloy.piko.instagram.audioShareUrlParserMethod
import io.github.nexalloy.piko.instagram.inAppBrowserOpenMethod
import io.github.nexalloy.piko.instagram.permalinkResponseParserMethod
import io.github.nexalloy.piko.instagram.permalinkResponseUrlField
import io.github.nexalloy.piko.instagram.profileShareUrlField
import io.github.nexalloy.piko.instagram.profileShareUrlParserMethod
import io.github.nexalloy.piko.instagram.shared.Links
import io.github.nexalloy.piko.instagram.thirdPartySharingUrlGetters
import kotlin.reflect.KProperty0

val SanitizeShareLinks = patch(
    name = "Sanitize share links",
    description = "Removes the tracking parameters from shared links of posts, reels, profiles, stories and lives.",
) {
    // Posts, reels, profiles and audio: the share url is parsed from the server response.
    val parsers = listOf(
        ::permalinkResponseParserMethod to ::permalinkResponseUrlField,
        ::profileShareUrlParserMethod to ::profileShareUrlField,
        ::audioShareUrlParserMethod to ::audioShareUrlField,
    ).count { (parser, field) -> runCatching { sanitizeParsedUrl(parser, field) }.isSuccess }

    // Stories and lives.
    val getters = ::thirdPartySharingUrlGetters.dexMethodList
    getters.forEach {
        it.hookMethod {
            after { param -> (param.result as? String)?.let { url -> param.result = Links.sanitizeUrl(url) } }
        }
    }

    if (parsers == 0 && getters.isEmpty()) throw Exception("No share links found")
}

private fun PatchExecutor.sanitizeParsedUrl(
    parser: KProperty0<FindMethodFunc>,
    urlField: KProperty0<FindFieldFunc>,
) {
    val field = urlField.field.apply { isAccessible = true }
    parser.hookMethod {
        after { param ->
            val response = listOf(param.result, param.thisObject).firstOrNull { field.declaringClass.isInstance(it) }
                ?: return@after
            val url = field.get(response) as? String ?: return@after
            field.set(response, Links.sanitizeUrl(url))
        }
    }
}

val OpenLinksExternally = patch(
    name = "Open links externally",
    description = "Opens links in your default browser instead of the in-app browser.",
    use = false,
) {
    ::inAppBrowserOpenMethod.hookMethod {
        before { param ->
            param.args.forEach { arg ->
                val url = arg as? String ?: return@forEach
                if (!url.startsWith("http")) return@forEach
                if (Links.openExternally(appContext, url)) {
                    param.result = true
                    return@before
                }
            }
        }
    }
}
