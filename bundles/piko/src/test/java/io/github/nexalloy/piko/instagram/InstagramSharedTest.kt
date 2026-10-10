package io.github.nexalloy.piko.instagram

import io.github.nexalloy.piko.instagram.shared.Links
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.shared.RequestFilter.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class InstagramSharedTest {
    @Test
    fun requestRules() {
        fun rule(url: String) = RequestFilter.ruleFor(URI(url))

        assertEquals(Rule.ANALYTICS, rule("https://graph.instagram.com/logging_client_events"))
        assertEquals(Rule.STORY_SEEN, rule("https://i.instagram.com/api/v2/media/seen/?reel=1"))
        assertEquals(Rule.STORIES, rule("https://i.instagram.com/api/v1/feed/reels_tray/"))
        assertEquals(Rule.EXPLORE, rule("https://i.instagram.com/api/v1/discover/topical_explore/"))
        assertEquals(Rule.COMMENTS, rule("https://i.instagram.com/api/v1/media/123/comments/"))
        assertEquals(Rule.DISCOVER_PEOPLE, rule("https://i.instagram.com/api/v1/discover/chaining/"))
        assertEquals(Rule.ADS, rule("https://i.instagram.com/api/v1/feed/injected_reels_media/"))
        assertEquals(Rule.HIGHLIGHTS, rule("https://i.instagram.com/api/v1/highlights/1/highlights_tray/"))
        assertNull(rule("https://i.instagram.com/api/v1/feed/timeline/"))

        val timeline = URI("https://i.instagram.com/api/v1/media/123/comments/")
        assertFalse(RequestFilter.shouldBlock(timeline))
        RequestFilter.enable(Rule.COMMENTS)
        assertTrue(RequestFilter.shouldBlock(timeline))
    }

    @Test
    fun blockIfNeeded() {
        class Request(var uri: URI)

        RequestFilter.enable(Rule.ADS)
        val ads = URI("https://i.instagram.com/api/v1/async_ads/")
        val request = Request(ads)
        assertEquals(ads, RequestFilter.blockIfNeeded(arrayOf(null, "x", request)))
        assertEquals(URI("https://127.0.0.1:9/api/v1/async_ads/"), request.uri)

        val args = arrayOf<Any?>(ads)
        assertEquals(ads, RequestFilter.blockIfNeeded(args))
        assertEquals("127.0.0.1", (args[0] as URI).host)

        val timeline = URI("https://i.instagram.com/api/v1/feed/timeline/")
        assertNull(RequestFilter.blockIfNeeded(arrayOf(Request(timeline))))
        assertNull(RequestFilter.blockIfNeeded(arrayOf("x")))
    }

    @Test
    fun sanitizeUrl() {
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            Links.sanitizeUrl("https://www.instagram.com/reel/abc/?igsh=MTc4MmM1YmI2Ng=="),
        )
        assertEquals(
            "https://www.instagram.com/p/abc/?img_index=2",
            Links.sanitizeUrl("https://www.instagram.com/p/abc/?igsh=x&img_index=2"),
        )
        assertEquals(
            "https://example.com/?id=1#top",
            Links.sanitizeUrl("https://example.com/?utm_source=ig&id=1&fbclid=x#top"),
        )
        assertEquals("https://example.com/a", Links.sanitizeUrl("https://example.com/a"))
        assertEquals("not a url", Links.sanitizeUrl("not a url"))
    }
}
