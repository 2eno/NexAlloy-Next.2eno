package io.github.nexalloy.piko.instagram.shared

import io.github.nexalloy.PatcherLog
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.tigonStartRequestMethods
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

    /**
     * Points a blocked request at an unreachable local address, so it fails like any network error.
     *
     * Piko throws an IOException inside startRequest, where Instagram's own try/catch handles it. A hook can only
     * throw from the call, past that catch, which crashes the request thread.
     *
     * @return the blocked uri, or null if the request is not blocked.
     */
    fun blockIfNeeded(args: Array<Any?>): URI? {
        args.forEachIndexed { index, arg ->
            if (arg == null) return@forEachIndexed
            if (arg is URI) {
                if (!shouldBlock(arg)) return null
                args[index] = unreachable(arg)
                return arg
            }
            uriFields(arg.javaClass).forEach { field ->
                val uri = field.get(arg) as? URI ?: return@forEach
                if (!shouldBlock(uri)) return null
                field.set(arg, unreachable(uri))
                return uri
            }
        }
        return null
    }

    // The discard port of the device itself refuses the connection right away.
    private fun unreachable(uri: URI) = URI("https", null, "127.0.0.1", 9, uri.path, null, null)

    private val uriFields = ConcurrentHashMap<Class<*>, Array<Field>>()

    private fun uriFields(clazz: Class<*>) = uriFields.getOrPut(clazz) {
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == URI::class.java }
            .onEach { it.isAccessible = true }
            .toList().toTypedArray()
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
                val uri = RequestFilter.blockIfNeeded(param.args) ?: return@before
                PatcherLog.info { "Blocked request: $uri" }
            }
        }
    }
}
