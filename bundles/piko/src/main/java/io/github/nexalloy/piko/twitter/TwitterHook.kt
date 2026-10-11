package io.github.nexalloy.piko.twitter

import io.github.nexalloy.piko.twitter.ads.HideAds
import io.github.nexalloy.piko.twitter.ads.HideRecommendationItems
import io.github.nexalloy.piko.twitter.ads.HideRecommendedUsers
import io.github.nexalloy.piko.twitter.ads.HideTopPeopleInSearch
import io.github.nexalloy.piko.twitter.ads.RemovePremiumUpsell
import io.github.nexalloy.piko.twitter.links.ClearTrackingParams
import io.github.nexalloy.piko.twitter.links.NoShortenedUrl
import io.github.nexalloy.piko.twitter.misc.DisableChirpFont
import io.github.nexalloy.piko.twitter.misc.HideFabMenuButtons
import io.github.nexalloy.piko.twitter.misc.PauseSearchSuggestions
import io.github.nexalloy.piko.twitter.misc.RemoveSearchSuggestions
import io.github.nexalloy.piko.twitter.misc.RestoreLegacyFollowerLists
import io.github.nexalloy.piko.twitter.timeline.HideBookmarkIcon
import io.github.nexalloy.piko.twitter.timeline.HideFollowedByContext
import io.github.nexalloy.piko.twitter.timeline.HideImmersivePlayer
import io.github.nexalloy.piko.twitter.timeline.HideLiveThreads
import io.github.nexalloy.piko.twitter.timeline.HidePromoteButton
import io.github.nexalloy.piko.twitter.timeline.RemoveViewCount
import io.github.nexalloy.piko.twitter.timeline.ShowSensitiveMedia

/**
 * X patches ported from Piko (https://github.com/crimera/piko).
 */
val TwitterPatches = arrayOf(
    // Ads
    HideAds,
    HideRecommendationItems,
    HideTopPeopleInSearch,
    HideRecommendedUsers,
    RemovePremiumUpsell,
    // Timeline
    ShowSensitiveMedia,
    HidePromoteButton,
    HideLiveThreads,
    RemoveViewCount,
    HideBookmarkIcon,
    HideImmersivePlayer,
    HideFollowedByContext,
    // Links
    ClearTrackingParams,
    NoShortenedUrl,
    // Misc
    DisableChirpFont,
    HideFabMenuButtons,
    RestoreLegacyFollowerLists,
    RemoveSearchSuggestions,
    PauseSearchSuggestions,
)
