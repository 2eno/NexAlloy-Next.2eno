package io.github.nexalloy.piko.twitter.links

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch
import io.github.nexalloy.piko.twitter.addSessionTokenMethod
import io.github.nexalloy.piko.twitter.shared.hookJsonModel
import io.github.nexalloy.piko.twitter.shared.instanceFields
import io.github.nexalloy.piko.twitter.shared.jsonModel
import java.lang.reflect.Field

val ClearTrackingParams = patch(
    name = "Clear tracking params",
    description = "Removes the tracking parameters from shared links.",
) {
    // addSessionToken(String link, shareParam, String sessionToken) returns the link unchanged.
    ::addSessionTokenMethod.hookMethod(object : XC_MethodReplacement() {
        override fun replaceHookedMethod(param: MethodHookParam) = param.args[0]
    })
}

internal object ShortUrls {
    private fun isShortUrl(url: String) = url.startsWith("https://t.co/") || url.startsWith("http://t.co/")

    /**
     * Replaces the t.co URL of a URL entity with its expanded URL, like Piko's `entity.e = entity.c`.
     */
    fun unshorten(entity: Any, fields: List<Field>) {
        val urls = fields.filter { it.type == String::class.java }.associateWith { it.get(entity) as String? }
        val short = urls.entries.firstOrNull { (_, url) -> url != null && isShortUrl(url) } ?: return
        val expanded = urls.values.firstOrNull { url -> url != null && url.startsWith("http") && !isShortUrl(url) }
            ?: return
        short.key.set(entity, expanded)
    }
}

val NoShortenedUrl = patch(
    name = "No shortened URL",
    description = "Opens links directly instead of through t.co short URLs.",
) {
    hookJsonModel(jsonModel("core.JsonUrlEntity")) { entity ->
        ShortUrls.unshorten(entity, instanceFields(entity.javaClass))
        entity
    }
}
