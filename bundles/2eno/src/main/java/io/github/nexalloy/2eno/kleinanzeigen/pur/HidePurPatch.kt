package io.github.nexalloy.`2eno`.kleinanzeigen.pur

import io.github.nexalloy.PatcherLog
import app.twoeno.extension.kleinanzeigen.HidePurPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier
import java.util.Locale

val HidePur = patch(
    name = "Hide Pur",
    description = "Hides the offers of the ad free subscription \"Kleinanzeigen Pur\".",
) {
    val remoteConfig = XposedHelpers.findClassIfExists(REMOTE_CONFIG_IMPL, classLoader)
    val hookedMethods = if (remoteConfig != null) hookRemoteConfig(remoteConfig) else hookFlagObjects()
    if (hookedMethods == 0) throw Exception("Pur flags not found")
}

/**
 * Reports all Pur flags as disabled.
 */
private fun hookRemoteConfig(remoteConfig: Class<*>) = XposedBridge.hookAllMethods(
    remoteConfig, "getBoolean", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (HidePurPatch.isPurFlag(param.args.firstOrNull())) param.result = false
        }
    }
).size

/**
 * Fallback if the remote config implementation is obfuscated: patch the flag objects themselves.
 * Their remote key is renamed so it is never found, and their default value is disabled.
 */
private fun PatchExecutor.hookFlagObjects(): Int {
    var hookedMethods = 0

    ::purFlagToStringMethods.dexMethodList.forEach { toString ->
        val flagClass = classLoader.loadClass(toString.className)
        if (flagClass.isInterface || Modifier.isAbstract(flagClass.modifiers)) return@forEach

        val flag = flagClass.declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == flagClass }
            ?.apply { isAccessible = true }
            ?.get(null) ?: return@forEach
        if (!flag.toString().startsWith(FLAG_NAME_PREFIX)) return@forEach

        flagClass.declaredMethods
            .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.name != "toString" }
            .forEach { method ->
                when (method.returnType) {
                    String::class.java -> {
                        method.isAccessible = true
                        val key = method.invoke(flag) as? String ?: return@forEach
                        if (!key.lowercase(Locale.ROOT).contains("ad_free_subscription")) return@forEach
                        method.hookMethod(XC_MethodReplacement.returnConstant("${key}_hidden"))
                    }

                    Any::class.java, Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType ->
                        method.hookMethod(XC_MethodReplacement.returnConstant(false))

                    else -> return@forEach
                }
                hookedMethods++
            }
        PatcherLog.info { "Hide Pur: found $flag (${flagClass.name})" }
    }

    return hookedMethods
}
