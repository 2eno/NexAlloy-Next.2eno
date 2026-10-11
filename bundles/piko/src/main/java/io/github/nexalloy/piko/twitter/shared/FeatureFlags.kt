package io.github.nexalloy.piko.twitter.shared

import io.github.nexalloy.patch
import io.github.nexalloy.piko.twitter.featureSwitchBooleanMethod
import java.util.concurrent.ConcurrentHashMap

/**
 * Overrides X feature switches, like Piko's FeatureSwitchPatch.
 */
object FeatureFlags {
    private val overrides = ConcurrentHashMap<String, Boolean>()

    fun override(vararg flags: Pair<String, Boolean>) {
        overrides.putAll(flags)
    }

    operator fun get(flag: String): Boolean? = overrides[flag]
}

/**
 * Hooks the feature switch reader of X, the flags are overridden by the patches using it.
 */
val HookFeatureFlags = patch(name = "<Hook feature flags>") {
    ::featureSwitchBooleanMethod.hookMethod {
        before { param ->
            val flag = param.args[0] as? String ?: return@before
            FeatureFlags[flag]?.let { param.result = it }
        }
    }
}
