package io.github.nexalloy.`2eno`.kleinanzeigen.pur

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val REMOTE_CONFIG_IMPL = "ebk.data.remote.remote_config.RemoteConfigImpl"

internal const val FLAG_NAME_PREFIX = "AdFreeSubscription"

/**
 * Remote config flags are Kotlin data objects in the `ebk.config` package.
 * The generated toString() of the Pur flags returns their name, e.g. "AdFreeSubscriptionEnabled".
 */
val purFlagToStringMethods = findMethodListDirect {
    findMethod {
        searchPackages("ebk.config")
        matcher {
            name = "toString"
            usingStrings(listOf(FLAG_NAME_PREFIX), StringMatchType.StartsWith)
        }
    }
}
