package io.github.nexalloy.`2eno`.untappd.ads

import app.twoeno.extension.untappd.HideSponsoredContentPatch
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val HideSponsoredContent = patch(
    name = "Hide sponsored content",
    description = "Removes sponsored beers, venues and posts from all lists.",
) {
    XposedHelpers.findMethodExact(
        "okhttp3.internal.http.RealInterceptorChain", classLoader, "proceed", "okhttp3.Request",
    ).hookMethod {
        after { it.result = HideSponsoredContentPatch.filterResponse(it.result) }
    }
}
