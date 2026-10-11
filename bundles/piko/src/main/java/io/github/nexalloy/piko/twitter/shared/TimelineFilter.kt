package io.github.nexalloy.piko.twitter.shared

import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes timeline entries by their entry id, like Piko's TimelineEntry patch.
 */
object TimelineFilter {
    enum class Rule {
        ADS,
        WHO_TO_FOLLOW,
        CREATORS_TO_SUBSCRIBE,
        COMMUNITIES_TO_JOIN,
        DETAILED_POSTS,
        REVISIT_BOOKMARKS,
        PINNED_POSTS,
        PREMIUM_PROMPT,
        TOP_PEOPLE_SEARCH,
        TODAYS_NEWS,
    }

    private val enabledRules: MutableSet<Rule> = ConcurrentHashMap.newKeySet()

    fun enable(vararg rules: Rule) {
        enabledRules.addAll(rules)
    }

    fun rulesFor(entryId: String): List<Rule> {
        val parts = entryId.split("-")
        val type = parts[0]
        if (type == "cursor" || type == "Guide" || type.startsWith("semantic_core")) return emptyList()
        return listOfNotNull(
            Rule.ADS.takeIf {
                "promoted" in entryId || "rtb" in entryId ||
                    type == "conversationthread" && parts.size == 3 ||
                    type == "superhero" || type == "eventsummary" ||
                    entryId.startsWith("main-event-") || type == "pivot"
            },
            Rule.DETAILED_POSTS.takeIf { type.startsWith("tweetdetail") },
            Rule.REVISIT_BOOKMARKS.takeIf { type == "bookmarked" },
            Rule.COMMUNITIES_TO_JOIN.takeIf { entryId.startsWith("community-to-join") },
            Rule.WHO_TO_FOLLOW.takeIf { entryId.startsWith("who-to-follow") },
            Rule.CREATORS_TO_SUBSCRIBE.takeIf { entryId.startsWith("who-to-subscribe") },
            Rule.PINNED_POSTS.takeIf { entryId.startsWith("pinned-tweets") },
            Rule.PREMIUM_PROMPT.takeIf { entryId.startsWith("messageprompt-") },
            Rule.TOP_PEOPLE_SEARCH.takeIf { type == "toptabsrpusermodule" },
            Rule.TODAYS_NEWS.takeIf { entryId.startsWith("stories") },
        )
    }

    fun shouldRemove(entryId: String) = rulesFor(entryId).any { it in enabledRules }

    private val entryIdFields = ConcurrentHashMap<Class<*>, Field>()

    /**
     * The entry id of timeline entries and module items, the field `a` in Piko's stubs.
     */
    internal fun entryId(entry: Any): String? = entryIdFields.getOrPut(entry.javaClass) {
        entry.javaClass.getDeclaredField("a").also {
            require(it.type == String::class.java) { "Unexpected entry id field $it" }
            it.isAccessible = true
        }
    }.get(entry) as String?
}

/**
 * Hooks the parsers of timeline entries, the rules are enabled by the patches using it.
 */
val FilterTimeline = patch(name = "<Filter timeline>") {
    listOf("JsonTimelineEntry", "JsonTimelineModuleItem").forEach { model ->
        hookJsonModel(jsonModel("timeline.urt.$model")) { entry ->
            val entryId = TimelineFilter.entryId(entry)
            if (entryId != null && TimelineFilter.shouldRemove(entryId)) null else entry
        }
    }
}
