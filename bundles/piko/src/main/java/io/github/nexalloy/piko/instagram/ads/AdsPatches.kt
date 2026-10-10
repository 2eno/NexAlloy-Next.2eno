package io.github.nexalloy.piko.instagram.ads

import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.activeBenefitCheckerMethod
import io.github.nexalloy.piko.instagram.adInjectorMethod
import io.github.nexalloy.piko.instagram.feedItemParseFromJsonMethod
import io.github.nexalloy.piko.instagram.shared.InterceptRequests
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.suggestedContentFields

val HideAds = patch(
    name = "Hide ads",
    description = "Removes ads from the feed, stories, reels and profiles.",
) {
    dependsOn(InterceptRequests)
    RequestFilter.enable(RequestFilter.Rule.ADS)

    ::adInjectorMethod.hookMethod(returnConstant(false))
}

val HideSuggestedContent = patch(
    name = "Hide suggested content",
    description = "Hides suggested stories, reels, threads and accounts in the feed. Suggested posts are still shown.",
) {
    val fields = ::suggestedContentFields.dexFieldList.map { it.toField().apply { isAccessible = true } }
    if (fields.isEmpty()) throw Exception("Suggested content fields not found")

    ::feedItemParseFromJsonMethod.hookMethod {
        after { param ->
            val item = param.result ?: return@after
            fields.forEach { field ->
                if (field.declaringClass.isInstance(item)) field.set(item, null)
            }
        }
    }
}

val UnlockPlusBenefits = patch(
    name = "Unlock Plus benefits",
    description = "Unlocks \"Plus\" subscription benefits that are checked locally. Use it at your own risk.",
    use = false,
) {
    ::activeBenefitCheckerMethod.hookMethod(returnConstant(true))
}
