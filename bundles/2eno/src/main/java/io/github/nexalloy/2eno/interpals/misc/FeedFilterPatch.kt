package io.github.nexalloy.`2eno`.interpals.misc

import app.twoeno.extension.interpals.FeedFilterPatch
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val FeedFilter = patch(
    name = "Feed age and gender filter",
    description = "Adds \"Age & gender\" to the filter of the feed.",
) {
    FeedFilterPatch.install(appContext)

    XposedHelpers.findMethodExact(
        "okhttp3.internal.http.RealInterceptorChain", classLoader, "proceed", "okhttp3.Request",
    ).hookMethod {
        before { it.args[0] = FeedFilterPatch.filterRequest(it.args[0]) }
    }
}
