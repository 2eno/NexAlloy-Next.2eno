package io.github.nexalloy.`2eno`.spotify.theme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Maps the Spotify colors to the Material You system colors.
 */
private class MaterialYouColors(context: Context) {
    private fun Context.systemColor(name: String, fallback: Int) = try {
        getColor(resources.getIdentifier(name, "color", "android"))
    } catch (_: Exception) {
        fallback
    }

    private val background = context.systemColor("system_neutral1_900", Color.BLACK)
    private val surface = context.systemColor("system_neutral1_800", Color.parseColor("#121212"))
    val accent = context.systemColor("system_accent1_200", Color.parseColor("#1DB954"))
    val accentPressed = context.systemColor("system_accent1_400", Color.parseColor("#1ABC54"))

    private val cache = ConcurrentHashMap<Int, Int>()

    fun map(color: Int): Int {
        if (color == 0) return 0
        return cache.getOrPut(color) {
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)

            val isGreen = green > 100 && green > red * 1.1 && green > blue * 1.1
            val isLightGray = red > 150 && abs(red - green) < 20 && abs(red - blue) < 20
            val isBlack = red <= 25 && green <= 25 && blue <= 25
            val isDarkGray = red in 26..70 && green in 26..70 && blue in 26..70

            val mapped = when {
                isGreen || isLightGray -> accent
                isBlack -> background
                isDarkGray -> surface
                else -> color
            }
            Color.argb(Color.alpha(color), Color.red(mapped), Color.green(mapped), Color.blue(mapped))
        }
    }
}

val MaterialYouTheme = patch(
    name = "Material You theme",
    description = "Replaces the Spotify green and the dark backgrounds with the system colors.",
) {
    val colors = MaterialYouColors(appContext)

    PorterDuffColorFilter::class.java.getConstructor(Int::class.java, PorterDuff.Mode::class.java).hookMethod {
        before { it.args[0] = colors.map(it.args[0] as Int) }
    }
    ColorStateList::class.java.getMethod("getColorForState", IntArray::class.java, Int::class.java).hookMethod {
        after { param ->
            val states = param.args[0] as IntArray? ?: intArrayOf()
            param.result = when {
                android.R.attr.state_pressed in states || android.R.attr.state_focused in states ->
                    Color.argb(77, Color.red(colors.accentPressed), Color.green(colors.accentPressed), Color.blue(colors.accentPressed))

                android.R.attr.state_selected in states -> colors.accent
                else -> colors.map(param.result as Int)
            }
        }
    }
    Color::class.java.getMethod("parseColor", String::class.java).hookMethod {
        after { it.result = colors.map(it.result as Int) }
    }
    Paint::class.java.getMethod("setColor", Int::class.java).hookMethod {
        before { it.args[0] = colors.map(it.args[0] as Int) }
    }
    GradientDrawable::class.java.getMethod("setColors", IntArray::class.java).hookMethod {
        before { param ->
            val gradient = param.args[0] as IntArray? ?: return@before
            for (i in gradient.indices) gradient[i] = colors.map(gradient[i])
        }
    }
    Resources::class.java.getMethod("getColor", Int::class.java, Resources.Theme::class.java).hookMethod {
        after { it.result = colors.map(it.result as Int) }
    }
    TypedArray::class.java.getMethod("getColor", Int::class.java, Int::class.java).hookMethod {
        after { it.result = colors.map(it.result as Int) }
    }

    // Remove the green tinted shadows and fades, which have no matching system color.
    View::class.java.getDeclaredMethod("onAttachedToWindow").hookMethod {
        after { param ->
            val view = param.thisObject as View
            val resourceName = try {
                view.resources.getResourceEntryName(view.id).lowercase(Locale.ROOT)
            } catch (_: Exception) {
                ""
            }
            if (view.javaClass == View::class.java &&
                listOf("shadow", "fade", "gradient").any { it in resourceName }
            ) {
                view.visibility = View.GONE
                view.layoutParams?.apply {
                    width = 0
                    height = 0
                }
            }
            if ("RecyclerView" in view.javaClass.name) {
                view.isHorizontalFadingEdgeEnabled = false
                view.isVerticalFadingEdgeEnabled = false
            }
        }
    }
}
