package io.github.nexalloy.piko.twitter.misc

import de.robv.android.xposed.XC_MethodReplacement.DO_NOTHING
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import io.github.nexalloy.patch
import io.github.nexalloy.piko.twitter.searchQuerySaveMethod
import io.github.nexalloy.piko.twitter.searchSuggestionsMethod
import io.github.nexalloy.piko.twitter.shared.FeatureFlags
import io.github.nexalloy.piko.twitter.shared.HookFeatureFlags

val DisableChirpFont = patch(
    name = "Disable chirp font",
    description = "Uses the system font instead of the Chirp font.",
    use = false,
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("af_ui_chirp_enabled" to false)
}

val HideFabMenuButtons = patch(
    name = "Hide FAB menu buttons",
    description = "Opens the post composer directly from the floating action button, without its menu.",
    use = false,
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("android_compose_fab_menu_enabled" to false)
}

val RestoreLegacyFollowerLists = patch(
    name = "Restore legacy follower lists",
    description = "Restores the legacy follower and following list screen.",
) {
    dependsOn(HookFeatureFlags)
    FeatureFlags.override("android_follower_timelines_stack_enabled" to false)
}

val RemoveSearchSuggestions = patch(
    name = "Remove search suggestions",
    description = "Removes the search suggestions in the explore section.",
    use = false,
) {
    ::searchSuggestionsMethod.hookMethod(returnConstant(null))
}

val PauseSearchSuggestions = patch(
    name = "Pause search suggestions",
    description = "Stops saving searches, so they are not suggested later.",
    use = false,
) {
    ::searchQuerySaveMethod.hookMethod(DO_NOTHING)
}
