package io.github.nexalloy.`2eno`.spotify.layout

import app.twoeno.extension.spotify.HidePremiumTabPatch
import io.github.nexalloy.patch
import io.github.nexalloy.`2eno`.spotify.navigationTabSetConstructor

val HidePremiumTab = patch(
    name = "Hide Premium tab",
    description = "Removes the \"Premium\" tab from the bottom navigation bar.",
) {
    ::navigationTabSetConstructor.hookMethod {
        before { param ->
            val args = param.args
            val tabs = HidePremiumTabPatch.filterTabs(args[0], args[1], args[2], args[3], args[4]) ?: return@before
            tabs.copyInto(args)
            args[5] = HidePremiumTabPatch.filterFlags(args[5] as Int)
        }
    }
}
