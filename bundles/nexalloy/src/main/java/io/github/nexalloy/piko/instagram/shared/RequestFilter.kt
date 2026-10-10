package io.github.nexalloy.piko.instagram.shared

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.tigonStartRequestMethods
import java.io.IOException
import java.lang.reflect.Field
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocks Instagram API requests by their endpoint.
 *
 * Endpoint list from Piko (https://github.com/crimera/piko), thanks to InstaEclipse and InstaMoon.
 */
object RequestFilter {
    enum class Rule {
        ANALYTICS,
        ONBOARDING_CONSENT,
        STORY_SEEN,
        LIVE_VIEWER,
        STORIES,
        EXPLORE,
        COMMENTS,
        DISCOVER_PEOPLE,
        ADS,
        HIGHLIGHTS,
    }

    private val enabledRules: MutableSet<Rule> = ConcurrentHashMap.newKeySet()

    fun enable(rule: Rule) {
        enabledRules.add(rule)
    }

    fun ruleFor(uri: URI): Rule? {
        val host = uri.host ?: ""
        val path = uri.path ?: return null
        return when {
            host.contains("graph.instagram.com") ||
                host.contains("graph.facebook.com") ||
                path.contains("/logging_client_events") -> Rule.ANALYTICS

            path.contains("/consent/existing_user_flow/") ||
                path.contains("/consent/new_user_flow/") -> Rule.ONBOARDING_CONSENT

            path.contains("/api/v2/media/seen/") -> Rule.STORY_SEEN

            path.contains("/heartbeat_and_get_viewer_count/") -> Rule.LIVE_VIEWER

            path.contains("/feed/reels_tray/") ||
                path.contains("feed/get_latest_reel_media/") ||
                path.contains("direct_v2/pending_inbox/?visual_message") ||
                path.contains("stories/hallpass/") ||
                path.contains("/api/v1/feed/reels_media_stream/") -> Rule.STORIES

            path.contains("/discover/topical_explore") ||
                host.contains("i.instagram.com") && path.contains("/fbsearch/recent_searches/") ||
                host.contains("i.instagram.com") && path.contains("/fbsearch/top_serp/") -> Rule.EXPLORE

            path.contains("/api/v1/media/") && path.contains("comments/") -> Rule.COMMENTS

            path.contains("/discover/ayml") || path.contains("/discover/chaining") -> Rule.DISCOVER_PEOPLE

            path.contains("profile_ads/get_profile_ads/") ||
                path.contains("/async_ads/") ||
                path.contains("/feed/injected_reels_media/") ||
                path.contains("/api/v1/ads/graphql/") -> Rule.ADS

            path.contains("/highlights_tray") -> Rule.HIGHLIGHTS

            else -> null
        }
    }

    fun shouldBlock(uri: URI): Boolean {
        if (enabledRules.isEmpty()) return false
        return ruleFor(uri)?.let { it in enabledRules } ?: false
    }

    private val uriFields = ConcurrentHashMap<Class<*>, Array<Field>>()

    /**
     * The request uri, either passed directly or held by one of the arguments.
     */
    fun findUri(args: Array<Any?>): URI? {
        args.forEach { arg ->
            if (arg == null) return@forEach
            if (arg is URI) return arg
            val fields = uriFields.getOrPut(arg.javaClass) {
                generateSequence(arg.javaClass) { it.superclass }
                    .takeWhile { it != Any::class.java }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter { it.type == URI::class.java }
                    .onEach { it.isAccessible = true }
                    .toList().toTypedArray()
            }
            fields.forEach { field -> (field.get(arg) as? URI)?.let { return it } }
        }
        return null
    }
}

/**
 * Hooks the request dispatcher of Instagram, the rules are enabled by the patches using it.
 */
val InterceptRequests = patch(name = "<Intercept requests>") {
    val methods = ::tigonStartRequestMethods.dexMethodList
    if (methods.isEmpty()) throw Exception("TigonServiceLayer.startRequest not found")

    methods.forEach {
        it.hookMethod {
            before { param ->
                val uri = RequestFilter.findUri(param.args) ?: return@before
                if (RequestFilter.shouldBlock(uri)) {
                    Logger.printDebug { "Blocked request: $uri" }
                    // The callers handle failed requests.
                    param.throwable = IOException("Block uri")
                }
            }
        }
    }
}
