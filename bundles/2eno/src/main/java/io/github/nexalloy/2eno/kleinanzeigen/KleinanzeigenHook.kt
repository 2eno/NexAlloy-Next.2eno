package io.github.nexalloy.`2eno`.kleinanzeigen

import io.github.nexalloy.`2eno`.kleinanzeigen.ads.HideAds
import io.github.nexalloy.`2eno`.kleinanzeigen.privacy.SanitizeSharingLinks
import io.github.nexalloy.`2eno`.kleinanzeigen.pur.HidePur

val KleinanzeigenPatches = arrayOf(HideAds, HidePur, SanitizeSharingLinks)
