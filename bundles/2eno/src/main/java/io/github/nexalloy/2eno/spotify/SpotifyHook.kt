package io.github.nexalloy.`2eno`.spotify

import io.github.nexalloy.`2eno`.spotify.ads.BlockPopupAds
import io.github.nexalloy.`2eno`.spotify.ads.HideAdSections
import io.github.nexalloy.`2eno`.spotify.ads.HideAdViews
import io.github.nexalloy.`2eno`.spotify.ads.HideContextMenuUpsells
import io.github.nexalloy.`2eno`.spotify.ads.HidePlaylistAds
import io.github.nexalloy.`2eno`.spotify.ads.HideVideoAds
import io.github.nexalloy.`2eno`.spotify.ads.MuteAudioAds
import io.github.nexalloy.`2eno`.spotify.layout.HidePremiumTab
import io.github.nexalloy.`2eno`.spotify.misc.FixThirdPartyLaunchersWidgets
import io.github.nexalloy.`2eno`.spotify.privacy.SanitizeSharingLinks
import io.github.nexalloy.`2eno`.spotify.theme.MaterialYouTheme
import io.github.nexalloy.`2eno`.spotify.theme.RoundedCorners

val SpotifyPatches = arrayOf(
    MuteAudioAds,
    HideAdSections,
    HideAdViews,
    HideContextMenuUpsells,
    BlockPopupAds,
    HidePlaylistAds,
    HideVideoAds,
    HidePremiumTab,
    FixThirdPartyLaunchersWidgets,
    SanitizeSharingLinks,
    MaterialYouTheme,
    RoundedCorners,
)
