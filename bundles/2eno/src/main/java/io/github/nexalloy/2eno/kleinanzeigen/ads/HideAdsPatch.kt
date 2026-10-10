package io.github.nexalloy.`2eno`.kleinanzeigen.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val HideAds = patch(
    name = "Hide ads",
    description = "Hides ads in the feed, search results and listings, and the promoted sellers in the feed.",
) {
    val adLookups = ::adLookupMethods.dexMethodList
        .filter { it.isMethod }
        .map { it.toMethod() }
        .filter { method ->
            !Modifier.isStatic(method.modifiers) && when (method.parameterCount) {
                4 -> !method.returnType.isPrimitive
                else -> Map::class.java.isAssignableFrom(method.returnType)
            }
        }
    if (adLookups.isEmpty()) throw Exception("Ad lookup methods not found")

    // Without a placement no ad is loaded.
    adLookups.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(null)) }

    // Promoted sellers: "Lokaler Anbieter" and their "Gesponsert" listing in the feed.
    val promotedSellerMappers = ::promotedSellerMapperMethods.dexMethodList
    if (promotedSellerMappers.isEmpty()) throw Exception("Promoted seller mapper not found")
    promotedSellerMappers.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(null)) }
}
