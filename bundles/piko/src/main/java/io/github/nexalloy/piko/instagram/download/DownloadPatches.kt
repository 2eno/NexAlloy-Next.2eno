package io.github.nexalloy.piko.instagram.download

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.MEDIA_CLASS
import io.github.nexalloy.piko.instagram.MEDIA_OPTION_CLASS
import io.github.nexalloy.piko.instagram.overflowMenuCreatorClass
import io.github.nexalloy.piko.instagram.overflowMenuOptionClickMethod
import io.github.nexalloy.piko.instagram.overflowMenuOptionsMethod
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

private const val DOWNLOAD_OPTION = "NEXALLOY_DOWNLOAD"
private const val DOWNLOAD_FOLDER = "Instagram"

/**
 * Creates an option of the post overflow menu. Like Piko's, it is an extra constant of `MediaOption$Option`,
 * created with the enum constructor `(name, ordinal, iconDrawable)`.
 */
private fun newMenuOption(optionClass: Class<*>, name: String, ordinal: Int, icon: Int): Any = try {
    optionClass.getDeclaredConstructor(String::class.java, Int::class.java, Int::class.java)
        .apply { isAccessible = true }
        .newInstance(name, ordinal, icon)
} catch (e: ReflectiveOperationException) {
    PatcherLog.info(e) { "Creating the menu option without its constructor" }
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, optionClass)!!.also { option ->
        Enum::class.java.getDeclaredField("name").apply { isAccessible = true }.set(option, name)
        Enum::class.java.getDeclaredField("ordinal").apply { isAccessible = true }.setInt(option, ordinal)
        optionClass.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == Int::class.java }
            .apply { isAccessible = true }.setInt(option, icon)
    }
}

private val objectFields = ConcurrentHashMap<Class<*>, List<Field>>()

/**
 * The object of [type] captured by the lambda [owner].
 */
private fun captured(owner: Any, type: Class<*>): Any? =
    objectFields.getOrPut(owner.javaClass) {
        owner.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .onEach { it.isAccessible = true }
    }.firstNotNullOfOrNull { field -> field.get(owner)?.takeIf(type::isInstance) }

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun enqueue(context: Context, items: List<MediaExtractor.Item>) {
    val downloads = context.getSystemService(DownloadManager::class.java)
    items.forEach { item ->
        val uri = Uri.parse(item.url)
        val extension = if (item.isVideo) ".mp4" else ".jpg"
        val name = uri.lastPathSegment?.takeIf { it.contains('.') }
            ?: "instagram_${System.currentTimeMillis()}$extension"
        downloads.enqueue(
            DownloadManager.Request(uri)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$DOWNLOAD_FOLDER/$name")
        )
    }
    toast(context, if (items.size == 1) "Downloading" else "Downloading ${items.size} items")
}

/**
 * Downloads [items], asking which items of a carousel to download.
 */
private fun download(activity: Activity, items: List<MediaExtractor.Item>) {
    if (items.isEmpty()) return toast(activity, "No media found")
    if (items.size == 1) return enqueue(activity, items)
    val choices = arrayOf("All (${items.size})") + items.indices.map { index ->
        "${index + 1}. ${if (items[index].isVideo) "Video" else "Photo"}"
    }
    AlertDialog.Builder(activity)
        .setTitle("Download")
        .setItems(choices) { _, which ->
            enqueue(activity, if (which == 0) items else listOf(items[which - 1]))
        }
        .show()
}

val DownloadMedia = patch(
    name = "Download media",
    description = "Adds a download option to the menu of posts. Files are saved to Download/Instagram.",
) {
    val optionClass = classLoader.loadClass(MEDIA_OPTION_CLASS)
    val mediaClass = classLoader.loadClass(MEDIA_CLASS)
    val creatorClass = ::overflowMenuCreatorClass.clazz
    val extractor = MediaExtractor(classLoader)

    // addOption(style, option, creator, CharSequence label, ArrayList options, boolean), adding Instagram's own options.
    val addOption = creatorClass.declaredMethods.single { method ->
        val parameters = method.parameterTypes
        Modifier.isStatic(method.modifiers) && parameters.size == 6 && parameters[0].isEnum &&
            parameters[1] == optionClass && parameters[2] == creatorClass &&
            parameters[3] == CharSequence::class.java && parameters[4] == ArrayList::class.java &&
            parameters[5] == Boolean::class.java
    }.apply { isAccessible = true }
    val style = addOption.parameterTypes[0].enumConstants!!.single { (it as Enum<*>).name == "NORMAL" }

    val icon = appContext.resources.getIdentifier("instagram_download_outline_24", "drawable", appContext.packageName)
    val option = newMenuOption(optionClass, DOWNLOAD_OPTION, 500, icon)

    ::overflowMenuOptionsMethod.hookMethod {
        after { param ->
            @Suppress("UNCHECKED_CAST")
            val options = param.result as? ArrayList<Any?> ?: return@after
            try {
                val creator = param.args.filterNotNull().firstNotNullOfOrNull { captured(it, creatorClass) }
                    ?: return@after
                val added = ArrayList<Any?>()
                addOption.invoke(null, style, option, creator, "Download", added, false)
                // Like Piko's, the option is the first of the menu.
                if (added.isNotEmpty() && options.none { it in added }) options.addAll(0, added)
            } catch (e: Exception) {
                PatcherLog.error(e) { "Could not add the download option" }
            }
        }
    }

    ::overflowMenuOptionClickMethod.hookMethod {
        before { param ->
            if (param.args[0] !== option) return@before
            param.result = null
            val helper = param.thisObject
            val activity = helper.javaClass.declaredFields.firstNotNullOfOrNull { field ->
                field.takeIf { Activity::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
                    ?.apply { isAccessible = true }?.get(helper) as Activity?
            } ?: return@before
            try {
                // The menu reads its post with a static getter of the helper.
                val media = helper.javaClass.declaredMethods.first { method ->
                    Modifier.isStatic(method.modifiers) && method.returnType == mediaClass &&
                        method.parameterTypes.contentEquals(arrayOf(helper.javaClass))
                }.apply { isAccessible = true }.invoke(null, helper) ?: return@before
                download(activity, extractor.items(media))
            } catch (e: Exception) {
                PatcherLog.error(e) { "Could not download the media" }
                toast(activity, "Download failed")
            }
        }
    }
}
