package io.github.nexalloy.piko.instagram.ads

import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.activeBenefitCheckerMethod
import io.github.nexalloy.piko.instagram.adInjectorMethod
import io.github.nexalloy.piko.instagram.feedItemParseFromJsonMethod
import io.github.nexalloy.piko.instagram.feedJsonKeyReaderMethod
import io.github.nexalloy.piko.instagram.shared.InterceptRequests
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.suggestedContentKeys
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

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
    // Like Piko, the keys of suggested content are renamed while a feed item is parsed, so the parser skips them.
    val reader = ::feedJsonKeyReaderMethod.method
    val keys = suggestedContentKeys.toHashSet()
    val parsingDepth = ThreadLocal<Int>()
    val hookedReaders = ConcurrentHashMap.newKeySet<Method>()

    fun hookReader(method: Method) {
        if (!hookedReaders.add(method)) return
        method.hookMethod {
            after { param ->
                if ((parsingDepth.get() ?: 0) > 0 && param.result in keys) param.result = "piko"
            }
        }
    }

    // The parser's key reader is usually abstract, so its implementation is hooked once the parser is known.
    fun implementation(clazz: Class<*>): Method? = generateSequence(clazz) { it.superclass }
        .firstNotNullOfOrNull { runCatching { it.getDeclaredMethod(reader.name, *reader.parameterTypes) }.getOrNull() }
        ?.takeUnless { Modifier.isAbstract(it.modifiers) }

    if (!Modifier.isAbstract(reader.modifiers)) hookReader(reader)

    ::feedItemParseFromJsonMethod.hookMethod {
        before { param ->
            parsingDepth.set((parsingDepth.get() ?: 0) + 1)
            val parser = param.args.firstOrNull { reader.declaringClass.isInstance(it) } ?: return@before
            implementation(parser.javaClass)?.let(::hookReader)
        }
        after { parsingDepth.set((parsingDepth.get() ?: 1) - 1) }
    }
}

val UnlockPlusBenefits = patch(
    name = "Unlock Plus benefits",
    description = "Unlocks \"Plus\" subscription benefits that are checked locally. Use it at your own risk.",
    use = false,
) {
    ::activeBenefitCheckerMethod.hookMethod(returnConstant(true))
}
