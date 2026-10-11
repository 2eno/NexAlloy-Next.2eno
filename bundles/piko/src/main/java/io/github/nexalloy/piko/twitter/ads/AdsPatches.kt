package io.github.nexalloy.piko.twitter.ads

import io.github.nexalloy.PatcherLog
import io.github.nexalloy.patch
import io.github.nexalloy.piko.twitter.profileRecommendationsField
import io.github.nexalloy.piko.twitter.profileRecommendationsMethod
import io.github.nexalloy.piko.twitter.shared.FeatureFlags
import io.github.nexalloy.piko.twitter.shared.FilterTimeline
import io.github.nexalloy.piko.twitter.shared.HookFeatureFlags
import io.github.nexalloy.piko.twitter.shared.TimelineFilter
import io.github.nexalloy.piko.twitter.shared.TimelineFilter.Rule
import io.github.nexalloy.piko.twitter.timelineTrendModelMethod
import io.github.nexalloy.piko.twitter.timelineTrendPromotedField

val HideAds = patch(
    name = "Hide ads",
    description = "Removes promoted posts, promoted trends and Google ads.",
) {
    dependsOn(FilterTimeline, HookFeatureFlags)
    TimelineFilter.enable(Rule.ADS)
    // Google ads.
    FeatureFlags.override("ssp_ads_dsp_client_context_enabled" to false)

    // Promoted trends are skipped while their model is created.
    runCatching {
        val promoted = ::timelineTrendPromotedField.field.apply { isAccessible = true }
        ::timelineTrendModelMethod.hookMethod {
            after { param ->
                // Piko reads the field from the trend or from the created model.
                val owner = listOf(param.thisObject, param.result).firstOrNull { promoted.declaringClass.isInstance(it) }
                if (owner != null && promoted.get(owner) != null) param.result = null
            }
        }
    }.onFailure { PatcherLog.error(it) { "Promoted trends are not hidden" } }
}

val HideRecommendationItems = patch(
    name = "Hide recommendation items",
    description = "Hides \"Who to follow\", \"Creators to subscribe\", \"Communities to join\", \"Today's news\", " +
        "revisited bookmarks and pinned posts, detailed posts and premium prompts in the timeline and replies.",
) {
    dependsOn(FilterTimeline)
    TimelineFilter.enable(
        Rule.WHO_TO_FOLLOW,
        Rule.CREATORS_TO_SUBSCRIBE,
        Rule.COMMUNITIES_TO_JOIN,
        Rule.DETAILED_POSTS,
        Rule.REVISIT_BOOKMARKS,
        Rule.PINNED_POSTS,
        Rule.PREMIUM_PROMPT,
        Rule.TODAYS_NEWS,
    )
}

val HideTopPeopleInSearch = patch(
    name = "Hide top people in search",
    description = "Hides the people module in the top search results.",
    use = false,
) {
    dependsOn(FilterTimeline)
    TimelineFilter.enable(Rule.TOP_PEOPLE_SEARCH)
}

val HideRecommendedUsers = patch(
    name = "Hide recommended users",
    description = "Hides the users recommended after following someone.",
) {
    val users = ::profileRecommendationsField.field.apply { isAccessible = true }
    require(List::class.java.isAssignableFrom(users.type)) { "Unexpected recommended users field $users" }
    ::profileRecommendationsMethod.hookMethod {
        before { param ->
            if (users.declaringClass.isInstance(param.thisObject)) users.set(param.thisObject, null)
        }
    }
}

val RemovePremiumUpsell = patch(
    name = "Remove premium upsell",
    description = "Removes the Premium upsells.",
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override(
        "subscriptions_upsells_premium_home_nav" to false,
        "subscriptions_enabled" to false,
        "subscriptions_upsells_get_verified_profile" to false,
        "subscriptions_upsells_get_verified_drawer_discount_enabled" to false,
        "subscriptions_upsells_get_verified_profile_fatigue_enabled" to false,
        "subscriptions_upsells_api_enabled" to false,
        "subscriptions_upsells_user_profile_name_migration_enabled" to false,
        "subscriptions_upsells_profile_card_enable" to false,
        "subscriptions_upsells_get_verified_drawer_card_enabled" to false,
        "subscriptions_upsells_get_verified_profile_discount_visitor_enabled" to false,
        "subscriptions_upsells_analytics_profile_enabled" to false,
        "subscriptions_upsells_articles_post_composer_promo_variant_enabled" to false,
        "subscriptions_upsells_bookmark_folders_enabled" to false,
        "subscriptions_upsells_verified_profile_visitor_upsell_enabled" to false,
        "subscriptions_upsells_verified_profile_visitor_upsell_redesign_enabled" to false,
        "subscriptions_upsells_get_verified_profile_card" to false,
        "subscriptions_upsells_get_verified_profile_discount_own_enabled" to false,
        "subscriptions_upsells_get_verified_profile_rotation_enabled" to false,
        "subscriptions_upsells_home_nav_migration_enabled" to false,
        "subscriptions_upsells_profile_card_enabled" to false,
        "subscriptions_upsells_quick_display_settings" to false,
        "subscriptions_upsells_track_interactions_enabled" to false,
        "subscriptions_upsells_user_profile_header_migration_enabled" to false,
    )
}
