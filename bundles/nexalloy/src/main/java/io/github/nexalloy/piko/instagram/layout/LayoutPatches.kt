package io.github.nexalloy.piko.instagram.layout

import android.view.View
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement.DO_NOTHING
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.Patch
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.clipsSwipeRefreshInterceptTouchMethod
import io.github.nexalloy.piko.instagram.clipsViewPagerGetViewAtIndexMethod
import io.github.nexalloy.piko.instagram.commentOnDoubleTapMethods
import io.github.nexalloy.piko.instagram.mediaNotesEnabledMethod
import io.github.nexalloy.piko.instagram.messageOnDoubleTapMethod
import io.github.nexalloy.piko.instagram.navigationButtonsListMethod
import io.github.nexalloy.piko.instagram.notesTagHash
import io.github.nexalloy.piko.instagram.notesTrayBuilderMethod
import io.github.nexalloy.piko.instagram.notesTrayRecyclerViewField
import io.github.nexalloy.piko.instagram.postOnDoubleTapMethod
import io.github.nexalloy.piko.instagram.reelOnDoubleTapMethod
import io.github.nexalloy.piko.instagram.storyProgressCompletedMethod
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

val HideReshareButton = patch(
    name = "Hide reshare button",
    description = "Hides the reshare button of posts and reels.",
    use = false,
) {
    ::mediaNotesEnabledMethod.hookMethod(returnConstant(false))

    // Newer versions read the flag from the live tree model.
    XposedHelpers.findClassIfExists("com.instagram.pando.livetree.LiveTreeJNI", classLoader)?.declaredMethods
        ?.filter { it.name == "getOptionalBooleanValueByHashCode" }
        ?.forEach { method ->
            method.hookMethod {
                before { param -> if (param.args.firstOrNull() == notesTagHash) param.result = false }
            }
        }
}

// region Navigation buttons

private val hiddenNavigationButtons: MutableSet<String> = ConcurrentHashMap.newKeySet()

private val navigationButtonNameFields = ConcurrentHashMap<Class<*>, List<Field>>()

/**
 * The name of the fragment opened by a navigation button, such as `fragment_clips`.
 */
private fun navigationButtonName(button: Any): String? {
    val fields = navigationButtonNameFields.getOrPut(button.javaClass) {
        button.javaClass.declaredFields
            .filter { it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
    }
    return fields.firstNotNullOfOrNull { (it.get(button) as? String)?.takeIf { name -> name.startsWith("fragment_") } }
}

private val HideNavigationButtons = patch(name = "<Hide navigation buttons>") {
    ::navigationButtonsListMethod.hookMethod {
        after { param ->
            val buttons = param.result as? List<*> ?: return@after
            param.result = buttons.filter { button ->
                button == null || navigationButtonName(button) !in hiddenNavigationButtons
            }
        }
    }
}

private fun hideNavigationButtonPatch(button: String, fragment: String): Patch = patch(
    name = "Hide $button button",
    description = "Hides the $button button of the navigation bar.",
    use = false,
) {
    dependsOn(HideNavigationButtons)
    hiddenNavigationButtons.add(fragment)
}

val HideReelsButton = hideNavigationButtonPatch("Reels", "fragment_clips")
val HideCreateButton = hideNavigationButtonPatch("Create", "fragment_share")
val HideSearchButton = hideNavigationButtonPatch("Search", "fragment_search")
val HideDirectButton = hideNavigationButtonPatch("Direct", "fragment_direct_tab")

// endregion

val HideNotesTray = patch(
    name = "Hide notes tray",
    description = "Hides the notes tray in the DM inbox.",
    use = false,
) {
    val recyclerView = ::notesTrayRecyclerViewField.field.apply { isAccessible = true }
    ::notesTrayBuilderMethod.hookMethod {
        after { param ->
            val owner = param.thisObject ?: return@after
            if (!recyclerView.declaringClass.isInstance(owner)) return@after
            (recyclerView.get(owner) as? View)?.visibility = View.GONE
        }
    }
}

val DisableDoubleTapLike = patch(
    name = "Disable double tap like",
    description = "Disables liking posts, reels, comments and messages by double tapping them.",
    use = false,
) {
    val targets = listOf(
        "post" to { listOf(::postOnDoubleTapMethod.member) },
        "reel" to { listOf(::reelOnDoubleTapMethod.member) },
        "comment" to { ::commentOnDoubleTapMethods.dexMethodList.map { it.toMember() } },
        "message" to { listOf(::messageOnDoubleTapMethod.member) },
    )
    val hooked = targets.count { (target, methods) ->
        runCatching {
            // Report the double tap as handled.
            methods().ifEmpty { throw Exception("No methods") }.forEach { it.hookMethod(returnConstant(true)) }
        }.onFailure { Logger.printInfo({ "Double tap on $target not found" }, it as? Exception) }.isSuccess
    }
    if (hooked == 0) throw Exception("No double tap listeners found")
}

val DisableReelsScrolling = patch(
    name = "Disable Reels scrolling",
    description = "Prevents swiping to the next reel.",
    use = false,
) {
    ::clipsViewPagerGetViewAtIndexMethod.hookMethod {
        before { param ->
            val viewPager = param.thisObject ?: return@before
            viewPager.javaClass.declaredFields
                .firstOrNull { it.type.name == "androidx.viewpager2.widget.ViewPager2" }
                ?.apply { isAccessible = true }?.get(viewPager)
                ?.let { it.javaClass.getMethod("setUserInputEnabled", Boolean::class.java).invoke(it, false) }
        }
    }
    ::clipsSwipeRefreshInterceptTouchMethod.hookMethod(returnConstant(false))
}

val DisableStoryFlipping = patch(
    name = "Disable story flipping",
    description = "Stops automatically moving to the next story.",
    use = false,
) {
    ::storyProgressCompletedMethod.hookMethod(DO_NOTHING)
}
