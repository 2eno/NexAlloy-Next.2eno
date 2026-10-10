package io.github.nexalloy.piko.instagram.network

import io.github.nexalloy.Patch
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.shared.InterceptRequests
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.shared.RequestFilter.Rule

private fun blockRequestsPatch(name: String, description: String, rule: Rule): Patch =
    patch(name, description, use = false) {
        dependsOn(InterceptRequests)
        RequestFilter.enable(rule)
    }

val DisableAnalytics = blockRequestsPatch(
    name = "Disable analytics",
    description = "Blocks analytics that are sent to Instagram/Facebook servers.",
    rule = Rule.ANALYTICS,
)

val DisableStories = blockRequestsPatch(
    name = "Disable stories",
    description = "Stops loading stories.",
    rule = Rule.STORIES,
)

val DisableHighlights = blockRequestsPatch(
    name = "Disable highlights",
    description = "Stops loading the story highlights on profiles.",
    rule = Rule.HIGHLIGHTS,
)

val DisableExplore = blockRequestsPatch(
    name = "Disable explore",
    description = "Stops loading the explore feed and search suggestions.",
    rule = Rule.EXPLORE,
)

val DisableComments = blockRequestsPatch(
    name = "Disable comments",
    description = "Stops loading comments.",
    rule = Rule.COMMENTS,
)

val DisableDiscoverPeople = blockRequestsPatch(
    name = "Disable discover people",
    description = "Stops loading the \"Discover people\" section on profiles.",
    rule = Rule.DISCOVER_PEOPLE,
)

val ViewLiveAnonymously = blockRequestsPatch(
    name = "View live anonymously",
    description = "Watch live videos without appearing in the viewer list.",
    rule = Rule.LIVE_VIEWER,
)
