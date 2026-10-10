package io.github.nexalloy.piko.instagram.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.util.Locale

/**
 * Link handling ported from Piko (https://github.com/crimera/piko).
 */
object Links {
    private const val FIRST_PARTY_DOMAIN = "instagram.com"

    /** Query parameters of Instagram links, that change what is opened. */
    private val functionalParameters = setOf("comment_id", "img_index", "open_comments", "story_media_id")

    /** Query parameters of other links, that only track the share. */
    private val trackingParameters = setOf("igsh", "igsi", "utm_source", "utm_medium", "utm_content", "fbclid", "si")

    /**
     * Removes all but functional query parameters from Instagram links
     * and the tracking parameters from other links.
     */
    fun sanitizeUrl(url: String): String = try {
        val uri = URI(url)
        val host = uri.host
        val rawQuery = uri.rawQuery
        if (host == null || rawQuery == null) {
            url
        } else {
            val normalizedHost = host.lowercase(Locale.ROOT)
            val firstParty = normalizedHost == FIRST_PARTY_DOMAIN || normalizedHost.endsWith(".$FIRST_PARTY_DOMAIN")
            val parameters = rawQuery.split('&').filter { parameter ->
                val name = parameter.substringBefore('=')
                when {
                    parameter.isEmpty() -> !firstParty
                    firstParty -> name in functionalParameters
                    else -> name !in trackingParameters
                }
            }
            val query = parameters.joinToString("&")
            if (query == rawQuery) url else replaceQuery(url, query, parameters.isNotEmpty())
        }
    } catch (_: Exception) {
        url
    }

    private fun replaceQuery(url: String, query: String, keepQueryDelimiter: Boolean): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart)
        val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
        val base = url.substring(0, queryStart)
        return if (keepQueryDelimiter) "$base?$query$fragment" else "$base$fragment"
    }

    /**
     * Opens the target of an Instagram redirect link (`https://l.instagram.com/?u=<url>&e=<tracking id>`)
     * in the default browser.
     *
     * @return If the link was opened.
     */
    fun openExternally(context: Context, url: String): Boolean {
        val target = runCatching { Uri.parse(url).getQueryParameter("u") }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitizeUrl(target)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
