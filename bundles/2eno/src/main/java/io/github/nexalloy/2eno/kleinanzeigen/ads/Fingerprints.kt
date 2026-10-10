package io.github.nexalloy.`2eno`.kleinanzeigen.ads

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val LIBERTY_PACKAGE = "de.kleinanzeigen.liberty"

/**
 * Ad placement lookups of the "Liberty" ad SDK, by page type (e.g. `LibertyPageType.PAGE_ATTR_HOME`):
 * `(LibertyPageType, position: Int, String, Boolean): AdPlacement?` and `(LibertyPageType, String): Map`.
 */
val adLookupMethods = findMethodListDirect {
    val pageType = findClass {
        searchPackages(LIBERTY_PACKAGE)
        matcher {
            superClass("java.lang.Enum")
            addField { name = "PAGE_ATTR_HOME" }
        }
    }.single().name

    findMethod {
        searchPackages(LIBERTY_PACKAGE)
        matcher { paramTypes(pageType, "int", "java.lang.String", "boolean") }
    } + findMethod {
        searchPackages(LIBERTY_PACKAGE)
        matcher { paramTypes(pageType, "java.lang.String") }
    }
}

/**
 * The mapper creating the promoted seller ("Lokaler Anbieter") and its "Gesponsert" listing
 * from the home feed. It returns null if the feed has no promoted seller.
 */
val promotedSellerMapperMethods = findMethodListDirect {
    val promotedSellerAd = findClass {
        searchPackages("ebk.ui.home")
        matcher { usingStrings(listOf("PromotedSellerAd(companyInfo="), StringMatchType.Equals) }
    }.single().name

    findMethod {
        searchPackages("ebk.ui.home")
        matcher {
            addInvoke {
                declaredClass(promotedSellerAd)
                name = "<init>"
            }
        }
    }.filter { it.isMethod && it.declaredClassName != promotedSellerAd && it.returnTypeName != "void" }
}
