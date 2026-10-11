package io.github.nexalloy.piko.twitter

import io.github.nexalloy.piko.twitter.links.ShortUrls
import io.github.nexalloy.piko.twitter.shared.TimelineFilter
import io.github.nexalloy.piko.twitter.shared.TimelineFilter.Rule
import io.github.nexalloy.piko.twitter.shared.instanceFields
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwitterSharedTest {
    @Test
    fun timelineRules() {
        fun rules(entryId: String) = TimelineFilter.rulesFor(entryId)

        assertEquals(listOf(Rule.ADS), rules("promoted-tweet-1234"))
        assertEquals(listOf(Rule.ADS), rules("conversationthread-1-2"))
        assertEquals(listOf(Rule.ADS), rules("superhero-1"))
        assertEquals(listOf(Rule.ADS), rules("main-event-1"))
        assertEquals(listOf(Rule.WHO_TO_FOLLOW), rules("who-to-follow-1234"))
        assertEquals(listOf(Rule.CREATORS_TO_SUBSCRIBE), rules("who-to-subscribe-1234"))
        assertEquals(listOf(Rule.COMMUNITIES_TO_JOIN), rules("community-to-join-1234"))
        assertEquals(listOf(Rule.DETAILED_POSTS), rules("tweetdetailrelatedtweets-1"))
        assertEquals(listOf(Rule.REVISIT_BOOKMARKS), rules("bookmarked-1"))
        assertEquals(listOf(Rule.PINNED_POSTS), rules("pinned-tweets-1"))
        assertEquals(listOf(Rule.PREMIUM_PROMPT), rules("messageprompt-1"))
        assertEquals(listOf(Rule.TOP_PEOPLE_SEARCH), rules("toptabsrpusermodule-1"))
        assertEquals(listOf(Rule.TODAYS_NEWS), rules("stories-1"))
        assertEquals(emptyList<Rule>(), rules("tweet-1234"))
        assertEquals(emptyList<Rule>(), rules("conversationthread-1"))
        assertEquals(emptyList<Rule>(), rules("cursor-top-promoted"))

        assertFalse(TimelineFilter.shouldRemove("who-to-follow-1"))
        TimelineFilter.enable(Rule.WHO_TO_FOLLOW)
        assertTrue(TimelineFilter.shouldRemove("who-to-follow-1"))
        assertFalse(TimelineFilter.shouldRemove("tweet-1"))
    }

    @Test
    fun timelineEntryId() {
        class JsonTimelineEntry(@JvmField var a: String?)

        assertEquals("tweet-1", TimelineFilter.entryId(JsonTimelineEntry("tweet-1")))
    }

    @Test
    fun unshortenUrls() {
        @Suppress("unused")
        class JsonUrlEntity(
            @JvmField var b: String?,
            @JvmField var c: String?,
            @JvmField var d: String?,
            @JvmField var e: String?,
        )

        val entity = JsonUrlEntity("example.com/a…", "https://example.com/a?b=c", null, "https://t.co/abc")
        ShortUrls.unshorten(entity, instanceFields(entity.javaClass))
        assertEquals("https://example.com/a?b=c", entity.e)
        assertEquals("https://example.com/a?b=c", entity.c)
        assertEquals("example.com/a…", entity.b)

        val unexpanded = JsonUrlEntity("t.co/abc", null, null, "https://t.co/abc")
        ShortUrls.unshorten(unexpanded, instanceFields(unexpanded.javaClass))
        assertEquals("https://t.co/abc", unexpanded.e)
    }
}
