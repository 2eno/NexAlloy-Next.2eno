package io.github.nexalloy.`2eno`.kleinanzeigen.privacy

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Builds the shared url with `?utm_source=...&utm_medium=social&utm_campaign=socialbuttons&...`.
 */
val sharingUrlBuilderMethods = findMethodListDirect {
    findMethod {
        searchPackages("ebk", "org", "de.kleinanzeigen")
        matcher {
            returnType = "java.lang.String"
            usingStrings(listOf("utm_campaign=socialbuttons"), StringMatchType.Contains)
        }
    }
}
