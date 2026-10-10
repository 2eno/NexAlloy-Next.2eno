package io.github.nexalloy.`2eno`.spotify.misc

import io.github.nexalloy.PatcherLog
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.`2eno`.spotify.canBindAppWidgetPermissionMethod

val FixThirdPartyLaunchersWidgets = patch(
    name = "Fix third party launchers widgets",
    description = "Allows the Spotify widgets to be added to third party launchers.",
) {
    // Only the stock launcher has the BIND_APPWIDGET permission Spotify checks for.
    val method = ::canBindAppWidgetPermissionMethod.memberOrNull
    if (method == null) {
        // Do not report this optional fix as failed.
        PatcherLog.info { "The BIND_APPWIDGET permission check was not found, skipping the widget fix" }
        return@patch
    }
    method.hookMethod(XC_MethodReplacement.returnConstant(true))
}
