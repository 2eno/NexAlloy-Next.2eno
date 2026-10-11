package io.github.nexalloy.piko.instagram.download

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.Toast
import io.github.nexalloy.HookDsl
import io.github.nexalloy.IScopedHookCallback
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.MEDIA_CLASS
import io.github.nexalloy.piko.instagram.MEDIA_OPTION_CLASS
import io.github.nexalloy.piko.instagram.overflowMenuCreatorClass
import io.github.nexalloy.piko.instagram.overflowMenuOptionClickMethod
import io.github.nexalloy.piko.instagram.overflowMenuOptionsMethod
import io.github.nexalloy.piko.instagram.reelsMenuBuilderClass
import io.github.nexalloy.piko.instagram.reelsMenuMethods
import io.github.nexalloy.scopedHook
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
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

/**
 * The activity [owner] keeps in a field.
 */
private fun activityIn(owner: Any): Activity? = owner as? Activity
    ?: owner.javaClass.declaredFields.firstNotNullOfOrNull { field ->
        field.takeIf { Activity::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            ?.apply { isAccessible = true }?.get(owner) as Activity?
    }

private const val REELS_MENU_MODULE = "ClipsOrganicMediaItemViewMoreOptionsController"

val DownloadMedia = patch(
    name = "Download media",
    description = "Adds a download option to the menu of posts and reels. Files are saved to Download/Instagram.",
) {
    val mediaClass = classLoader.loadClass(MEDIA_CLASS)
    val extractor = MediaExtractor(classLoader)
    val icon = appContext.resources.getIdentifier("instagram_download_outline_24", "drawable", appContext.packageName)

    fun downloadMedia(activity: Activity, media: Any) {
        try {
            download(activity, extractor.items(media))
        } catch (e: Exception) {
            PatcherLog.error(e) { "Could not download the media" }
            toast(activity, "Download failed")
        }
    }

    // The overflow menu of posts in the feed.
    val feedMenu = runCatching {
        val optionClass = classLoader.loadClass(MEDIA_OPTION_CLASS)
        val creatorClass = ::overflowMenuCreatorClass.clazz

        // addOption(style, option, creator, CharSequence label, ArrayList options, boolean), adding Instagram's own options.
        val addOption = creatorClass.declaredMethods.single { method ->
            val parameters = method.parameterTypes
            Modifier.isStatic(method.modifiers) && parameters.size == 6 && parameters[0].isEnum &&
                parameters[1] == optionClass && parameters[2] == creatorClass &&
                parameters[3] == CharSequence::class.java && parameters[4] == ArrayList::class.java &&
                parameters[5] == Boolean::class.java
        }.apply { isAccessible = true }
        val style = addOption.parameterTypes[0].enumConstants!!.single { (it as Enum<*>).name == "NORMAL" }
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
                val activity = activityIn(helper) ?: return@before
                // The menu reads its post with a static getter of the helper.
                val media = runCatching {
                    helper.javaClass.declaredMethods.first { method ->
                        Modifier.isStatic(method.modifiers) && method.returnType == mediaClass &&
                            method.parameterTypes.contentEquals(arrayOf(helper.javaClass))
                    }.apply { isAccessible = true }.invoke(null, helper)
                }.onFailure { PatcherLog.error(it) { "Could not read the post of the menu" } }.getOrNull()
                if (media == null) toast(activity, "Download failed") else downloadMedia(activity, media)
            }
        }
    }.onFailure { PatcherLog.error(it) { "No download option in the post menu" } }

    // The menu of reels and of posts shown like reels, such as in the "For you" feed. Like Piko's, the option is
    // added to the bottom sheet builder right after the menu creates it.
    val reelsMenu = runCatching {
        val builderClass = ::reelsMenuBuilderClass.clazz
        // addItem(Context, OnClickListener, String label, int icon, boolean), what Piko's reels button calls.
        val addItem = builderClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterTypes.contentEquals(
                arrayOf(
                    Context::class.java, View.OnClickListener::class.java, String::class.java,
                    Int::class.java, Boolean::class.java,
                )
            )
        }.apply { isAccessible = true }

        val handledBuilders: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
        val addDownload: HookDsl<IScopedHookCallback>.() -> Unit = {
            after { param ->
                // Only the builder of the menu itself, once even when both menu methods are running.
                if (REELS_MENU_MODULE !in param.args || !handledBuilders.add(param.thisObject)) return@after
                try {
                    val owners = listOfNotNull(outerParam.thisObject) + outerParam.args.filterNotNull()
                    val media = owners.firstOrNull { mediaClass.isInstance(it) }
                        ?: owners.firstNotNullOfOrNull { captured(it, mediaClass) }
                        ?: return@after
                    val activity = owners.firstNotNullOfOrNull(::activityIn)
                        ?: (owners.firstOrNull { it is View } as View?)?.context as? Activity
                        ?: return@after
                    val onClick = View.OnClickListener { downloadMedia(activity, media) }
                    addItem.invoke(param.thisObject, activity, onClick, "Download", icon, false)
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download option to the reels menu" }
                }
            }
        }
        val builderConstructors = builderClass.declaredConstructors.map { it to addDownload }.toTypedArray()
        val menuMethods = ::reelsMenuMethods.dexMethodList.ifEmpty { throw Exception("Reels menu not found") }
        menuMethods.forEach { it.hookMethod(scopedHook(*builderConstructors)) }
    }.onFailure { PatcherLog.error(it) { "No download option in the reels menu" } }

    if (feedMenu.isFailure && reelsMenu.isFailure) throw Exception("No menu found for the download option")
}
