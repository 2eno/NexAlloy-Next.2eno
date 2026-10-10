package io.github.nexalloy.piko.instagram

import io.github.nexalloy.piko.instagram.ads.HideAds
import io.github.nexalloy.piko.instagram.ads.HideSuggestedContent
import io.github.nexalloy.piko.instagram.ads.UnlockPlusBenefits
import io.github.nexalloy.piko.instagram.layout.DisableDoubleTapLike
import io.github.nexalloy.piko.instagram.layout.DisableReelsScrolling
import io.github.nexalloy.piko.instagram.layout.DisableStoryFlipping
import io.github.nexalloy.piko.instagram.layout.HideCreateButton
import io.github.nexalloy.piko.instagram.layout.HideDirectButton
import io.github.nexalloy.piko.instagram.layout.HideNotesTray
import io.github.nexalloy.piko.instagram.layout.HideReelsButton
import io.github.nexalloy.piko.instagram.layout.HideReshareButton
import io.github.nexalloy.piko.instagram.layout.HideSearchButton
import io.github.nexalloy.piko.instagram.links.OpenLinksExternally
import io.github.nexalloy.piko.instagram.links.SanitizeShareLinks
import io.github.nexalloy.piko.instagram.misc.DisableOnboardingPermissionPrompts
import io.github.nexalloy.piko.instagram.misc.DisableVideoAutoplay
import io.github.nexalloy.piko.instagram.misc.ImproveImageViewing
import io.github.nexalloy.piko.instagram.misc.LimitFeedToFollowingProfiles
import io.github.nexalloy.piko.instagram.misc.RemoveBuildExpiredPopup
import io.github.nexalloy.piko.instagram.misc.StoriesAudioAutoplay
import io.github.nexalloy.piko.instagram.misc.UnlockDeveloperOptions
import io.github.nexalloy.piko.instagram.network.DisableAnalytics
import io.github.nexalloy.piko.instagram.network.DisableComments
import io.github.nexalloy.piko.instagram.network.DisableDiscoverPeople
import io.github.nexalloy.piko.instagram.network.DisableExplore
import io.github.nexalloy.piko.instagram.network.DisableHighlights
import io.github.nexalloy.piko.instagram.network.DisableStories
import io.github.nexalloy.piko.instagram.network.ViewLiveAnonymously
import io.github.nexalloy.piko.instagram.privacy.DisableScreenshotDetection
import io.github.nexalloy.piko.instagram.privacy.DisableTypingStatus
import io.github.nexalloy.piko.instagram.privacy.ViewDmsAnonymously
import io.github.nexalloy.piko.instagram.privacy.ViewStoriesAnonymously

/**
 * Instagram patches ported from Piko (https://github.com/crimera/piko).
 */
val InstagramPatches = arrayOf(
    // Ads
    HideAds,
    HideSuggestedContent,
    UnlockPlusBenefits,
    // Content
    DisableAnalytics,
    DisableStories,
    DisableHighlights,
    DisableExplore,
    DisableComments,
    DisableDiscoverPeople,
    // Privacy
    ViewStoriesAnonymously,
    ViewLiveAnonymously,
    ViewDmsAnonymously,
    DisableTypingStatus,
    DisableScreenshotDetection,
    // Links
    SanitizeShareLinks,
    OpenLinksExternally,
    // Layout
    HideReshareButton,
    HideReelsButton,
    HideCreateButton,
    HideSearchButton,
    HideDirectButton,
    HideNotesTray,
    DisableDoubleTapLike,
    DisableReelsScrolling,
    DisableStoryFlipping,
    // Misc
    DisableVideoAutoplay,
    StoriesAudioAutoplay,
    DisableOnboardingPermissionPrompts,
    RemoveBuildExpiredPopup,
    LimitFeedToFollowingProfiles,
    ImproveImageViewing,
    UnlockDeveloperOptions,
)
