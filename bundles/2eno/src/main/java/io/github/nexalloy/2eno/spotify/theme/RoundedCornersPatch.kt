package io.github.nexalloy.`2eno`.spotify.theme

import android.content.res.Resources
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import io.github.nexalloy.PatcherLog
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.util.Locale

private fun dp(value: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, Resources.getSystem().displayMetrics)

private val cornerRadius = dp(28f)
private val pillRadius = dp(100f)

/**
 * Rounds all corners, or only the top corners for bottom sheets.
 */
private class RoundedOutline(private val topOnly: Boolean) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val extraHeight = if (topOnly) cornerRadius.toInt() else 0
        outline.setRoundRect(0, 0, view.width, view.height + extraHeight, cornerRadius)
    }
}

private val roundedOutline = RoundedOutline(topOnly = false)
private val roundedTopOutline = RoundedOutline(topOnly = true)

private fun roundCorners(view: View) {
    val resourceName = try {
        view.resources.getResourceEntryName(view.id)
    } catch (_: Exception) {
        ""
    }
    val className = view.javaClass.name.lowercase(Locale.ROOT)

    // Profile pictures are already round.
    if ("faceview" in className || "face" in resourceName) return

    val isImage = view is ImageView || "imageview" in className
    val isArtwork = listOf("header", "cover", "art", "entity").any { it in resourceName }
    val isSheet = "bottomsheet" in className || "sheet" in resourceName || "queue" in resourceName
    val isCard = "card" in className || "tile" in resourceName
    val isSearchBar = resourceName == "browse_search_bar_container" || "search" in resourceName
    val isSeekBar = resourceName == "seek_frame" || "seek" in resourceName
    // List rows contain images that are rounded on their own.
    val isRow = ("row" in resourceName || "item" in resourceName) && !isImage && !isSheet

    if (!(isImage || isArtwork || isSheet || isCard || isSearchBar || isSeekBar)) return
    if (isRow) {
        view.clipToOutline = false
    } else {
        view.clipToOutline = true
        view.outlineProvider = if (isSheet) roundedTopOutline else roundedOutline
    }
}

val RoundedCorners = patch(
    name = "Rounded corners",
    description = "Rounds the corners of artwork, cards, sheets and the search bar.",
) {
    View::class.java.getDeclaredMethod("onAttachedToWindow").hookMethod {
        after { roundCorners(it.thisObject as View) }
    }
    ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java).hookMethod {
        after { roundCorners(it.thisObject as View) }
    }
    GradientDrawable::class.java.getMethod("setCornerRadius", Float::class.java).hookMethod {
        before { param ->
            val radius = param.args[0] as Float
            param.args[0] = if (radius > dp(15f)) pillRadius else cornerRadius
        }
    }

    // Bundled Material components, skipped if they are obfuscated.
    runCatching {
        XposedHelpers.findMethodExact(
            "com.google.android.material.bottomsheet.BottomSheetBehavior", classLoader, "onLayoutChild",
            "androidx.coordinatorlayout.widget.CoordinatorLayout", View::class.java, Int::class.java,
        ).hookMethod {
            after { param ->
                val sheet = param.args[1] as View
                sheet.clipToOutline = true
                sheet.outlineProvider = roundedTopOutline
            }
        }
        XposedHelpers.findMethodExact(
            "com.google.android.material.shape.MaterialShapeDrawable", classLoader, "setInterpolation",
            Float::class.java,
        ).hookMethod {
            after { XposedHelpers.callMethod(it.thisObject, "setCornerSize", cornerRadius) }
        }
    }.onFailure { PatcherLog.info { "Rounded corners: Material components not found: $it" } }
}
