package io.github.nexalloy.piko.twitter

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.opcodeEnum
import io.github.nexalloy.piko.inDexOrder
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

// Ported from the Piko X patches (https://github.com/crimera/piko).

internal const val JSON_MODEL_PACKAGE = "com.twitter.model.json"

private fun DexKitBridge.methodsUsingStrings(vararg strings: String) = findMethod {
    matcher { usingStrings(*strings) }
}

/**
 * The first method of [className] reading a field, like Piko's fingerprints matching the class and `iget-object`.
 */
private fun DexKitBridge.firstMethodReadingField(className: String): MethodData =
    getClassData(className)!!.methods.inDexOrder().first { method ->
        method.instructions.any { it.opcodeEnum == Opcode.IGET_OBJECT }
    }

private fun MethodData.fieldsRead() = instructions.filter { it.opcodeEnum == Opcode.IGET_OBJECT }.map { it.fieldRef!! }

// region Feature switches

/**
 * `getBoolean(String name, boolean default)` of the class reading the feature switches.
 */
val featureSwitchBooleanMethod = findMethodDirect {
    methodsUsingStrings("feature_switches_configs_crashlytics_enabled")
        .mapNotNull { it.declaredClass }.distinctBy { it.name }
        .firstNotNullOf { featureSwitches ->
            featureSwitches.methods.inDexOrder().firstOrNull {
                it.returnTypeName == "boolean" && it.paramTypeNames == listOf("java.lang.String", "boolean")
            }
        }
}

// endregion

// region Ads

/**
 * Converts a JSON trend to its model, Piko skips trends with promoted metadata.
 */
val timelineTrendModelMethod = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("$JSON_MODEL_PACKAGE.timeline.urt.JsonTimelineTrend")
            returnType = "java.lang.Object"
        }
    }.inDexOrder().first()
}

/**
 * The promoted metadata of a trend, read three instructions after the model is created.
 */
val timelineTrendPromotedField = findFieldDirect {
    val instructions = timelineTrendModelMethod().instructions
    val newInstance = instructions.indexOfLast { it.opcodeEnum == Opcode.NEW_INSTANCE }
    instructions[newInstance + 3].fieldRef!!
}

/**
 * Reads the users recommended after following someone.
 */
val profileRecommendationsMethod = findMethodDirect {
    firstMethodReadingField("$JSON_MODEL_PACKAGE.people.JsonProfileRecommendationModuleResponse")
}

val profileRecommendationsField = findFieldDirect {
    profileRecommendationsMethod().fieldsRead().last()
}

// endregion

// region Timeline

/**
 * Reads the live threads (fleets) shown above the home timeline.
 */
val liveThreadsMethod = findMethodDirect {
    firstMethodReadingField("com.twitter.fleets.api.json.JsonFleetsTimelineResponse")
}

val liveThreadsField = findFieldDirect {
    liveThreadsMethod().fieldsRead().first()
}

val socialProofDataMethod = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("SocialProofView", StringMatchType.EndsWith)
            name = "setSocialProofData"
        }
    }.single()
}

// endregion

// region Links

/**
 * Appends the share parameter and the session token to shared links.
 */
val addSessionTokenMethod = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.String"
            paramCount = 3
            usingStrings("<this>", "shareParam", "sessionToken")
        }
    }.single { it.paramTypeNames.first() == "java.lang.String" && it.paramTypeNames.last() == "java.lang.String" }
}

// endregion

// region Search

val searchSuggestionsMethod = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(".search.provider.", StringMatchType.Contains)
            returnType = "java.util.Collection"
            usingStrings("type", "query_id")
        }
    }.single()
}

/**
 * Saves a search query, so it is suggested later.
 */
val searchQuerySaveMethod = findMethodDirect {
    methodsUsingStrings(
        "search_queries",
        "findSearchQuery: ",
        "LOWER(query)=LOWER(?) AND LOWER(name)=LOWER(?) AND type=? AND latitude=? AND longitude=?",
    ).single { it.returnTypeName == "void" }
}

// endregion
