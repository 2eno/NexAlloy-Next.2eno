package io.github.nexalloy.piko.instagram.download

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import io.github.nexalloy.HookDsl
import io.github.nexalloy.IScopedHookCallback
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.MEDIA_CLASS
import io.github.nexalloy.piko.instagram.MEDIA_OPTION_CLASS
import io.github.nexalloy.piko.instagram.overflowMenuCreatorClass
import io.github.nexalloy.piko.instagram.overflowMenuOptionClickMethod
import io.github.nexalloy.piko.instagram.overflowMenuOptionsMethod
import io.github.nexalloy.piko.instagram.SHEET_MENU_MODULES
import io.github.nexalloy.piko.instagram.mediaCarouselGetter
import io.github.nexalloy.piko.instagram.mediaImageInfoGetter
import io.github.nexalloy.piko.instagram.mediaVideoVersionsGetter
import io.github.nexalloy.piko.instagram.sheetMenuBuilderClass
import io.github.nexalloy.piko.instagram.sheetMenuMethods
import io.github.nexalloy.piko.instagram.storyMenuLabelsMethod
import io.github.nexalloy.piko.instagram.storyMenuRowConstructor
import io.github.nexalloy.piko.instagram.storyMenuSheetMethod
import io.github.nexalloy.piko.instagram.igDialogBuildMethod
import io.github.nexalloy.piko.instagram.igDialogCancelableMethod
import io.github.nexalloy.piko.instagram.igDialogCanceledOnTouchOutsideMethod
import io.github.nexalloy.piko.instagram.igDialogMenuBottomCornersField
import io.github.nexalloy.piko.instagram.igDialogNegativeButtonMethod
import io.github.nexalloy.piko.instagram.igDialogTitleField
import io.github.nexalloy.piko.instagram.highlightMenuAddMethod
import io.github.nexalloy.piko.instagram.highlightMenuBuilderClass
import io.github.nexalloy.piko.instagram.highlightMenuMethod
import io.github.nexalloy.piko.instagram.mediaPositionField
import io.github.nexalloy.piko.instagram.profilePictureActionConstructor
import io.github.nexalloy.piko.instagram.profilePictureActionsMethod
import io.github.nexalloy.piko.instagram.videoVersionHeightGetter
import io.github.nexalloy.piko.instagram.videoVersionWidthGetter
import io.github.nexalloy.scopedHook
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val DOWNLOAD_OPTION = "NEXALLOY_DOWNLOAD"
private const val DOWNLOAD_FOLDER = "Instagram"
private const val DOWNLOAD_LABEL = "Download options"
private const val PROFILE_PICTURE_LABEL = "Download"
private const val HIGHLIGHT_COVER_LABEL = "Download highlight cover"
private const val HIGHLIGHT_MENU_MODULE = "HighlightsTrayDialogHelper"

/**
 * Logs what the download hooks do, so a missing download option can be traced in the logcat (tag NexAlloy).
 */
private fun log(message: String) {
    Log.i("NexAlloy", "Download media: $message")
}

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

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * The file name of [item], the last path segment of its URL or a new name.
 */
private fun fileName(item: MediaExtractor.Item): String {
    val extension = if (item.isVideo) ".mp4" else ".jpg"
    val name = Uri.parse(item.url).lastPathSegment?.takeIf { it.contains('.') }
        ?: "instagram_${System.currentTimeMillis()}$extension"
    // A cover image keeps the name of its video file otherwise.
    return if (!item.isVideo && !name.endsWith(".jpg") && !name.endsWith(".webp") && !name.endsWith(".heic")) "$name.jpg"
    else name
}

/**
 * Saves the file of [item] to Download/Instagram. Like Piko, the file is fetched by the app itself, as not every
 * device has the download manager.
 */
private fun save(context: Context, item: MediaExtractor.Item) {
    val name = fileName(item)
    val mimeType = if (item.isVideo) "video/mp4" else if (name.endsWith(".webp")) "image/webp" else "image/jpeg"
    val connection = URL(item.url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        connection.inputStream.use { input ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore did not create $name")
                try {
                    resolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER)
                folder.mkdirs()
                File(folder, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    } finally {
        connection.disconnect()
    }
    log("saved $name")
}

private fun enqueue(context: Context, items: List<MediaExtractor.Item>) {
    val appContext = context.applicationContext ?: context
    toast(context, if (items.size == 1) "Downloading" else "Downloading ${items.size} items")
    Thread {
        var saved = 0
        items.forEach { item ->
            try {
                save(appContext, item)
                saved++
            } catch (e: Exception) {
                log("failed to save ${item.url}: ${e.stackTraceToString().take(1500)}")
            }
        }
        val text = when {
            saved == items.size -> "Saved to Download/$DOWNLOAD_FOLDER"
            saved == 0 -> "Download failed"
            else -> "Saved $saved of ${items.size} to Download/$DOWNLOAD_FOLDER"
        }
        mainHandler.post { toast(appContext, text) }
    }.apply { name = "NexAlloy download" }.start()
}

/**
 * The photo of [item], the cover image when it is a video.
 */
private fun asImage(item: MediaExtractor.Item) = item.imageUrl?.let {
    MediaExtractor.Item(it, isVideo = false, imageUrl = it, item.imageVariants)
}

private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Media link", url))
    toast(context, "Copied media link")
}

private fun openExternally(activity: Activity, url: String, isVideo: Boolean) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), if (isVideo) "video/*" else "image/*")
    try {
        activity.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        toast(activity, "No app found to open the media")
    }
}

/**
 * Shows the sizes of a photo or video to download one of them.
 */
private fun showVariants(
    dialog: InstagramDialog,
    activity: Activity,
    title: String,
    variants: List<MediaExtractor.Variant>,
    isVideo: Boolean,
) = dialog.show(activity, title, variants.map { variant ->
    variant.label to {
        enqueue(activity, listOf(MediaExtractor.Item(variant.url, isVideo, imageUrl = variant.url.takeIf { !isVideo })))
    }
})

/**
 * Shows Piko's download options for the item at [position], the item of a carousel on screen.
 */
private fun download(
    dialog: InstagramDialog,
    activity: Activity,
    items: List<MediaExtractor.Item>,
    position: Int,
    extraOptions: List<Pair<String, () -> Unit>>,
) {
    if (items.isEmpty()) return toast(activity, "No media found")
    val current = items[position.takeIf { it in items.indices } ?: 0]
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Download current media" to { enqueue(activity, listOf(current)) }
    asImage(current)?.let { image -> options += "Download as image" to { enqueue(activity, listOf(image)) } }
    options += "Copy media link" to { copyLink(activity, current.url) }
    if (current.imageVariants.isNotEmpty()) {
        options += "Image variants" to {
            showVariants(dialog, activity, "Image variants", current.imageVariants, isVideo = false)
        }
    }
    if (current.isVideo) {
        if (current.videoVariants.isNotEmpty()) {
            options += "Video variants" to {
                showVariants(dialog, activity, "Video variants", current.videoVariants, isVideo = true)
            }
        }
        options += "Open video externally" to { openExternally(activity, current.url, isVideo = true) }
    } else {
        options += "Open image externally" to { openExternally(activity, current.url, isVideo = false) }
    }
    if (items.size > 1) options += "Download all" to { enqueue(activity, items) }
    options += extraOptions
    dialog.show(activity, "Download options", options)
}

/**
 * The activity [owner] is, wraps, or keeps in a field.
 */
private fun activityIn(owner: Any): Activity? {
    var context = owner as? Context ?: (owner as? View)?.context
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return owner.javaClass.declaredFields.firstNotNullOfOrNull { field ->
        field.takeIf { Activity::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            ?.apply { isAccessible = true }?.get(owner) as Activity?
    }
}

val DownloadMedia = patch(
    name = "Download media",
    description = "Adds download options to the menus of posts, reels and stories, and to profile pictures. Files are saved to Download/Instagram.",
) {
    val mediaClass = classLoader.loadClass(MEDIA_CLASS)
    fun <T> optional(name: String, find: () -> T) =
        runCatching(find).onFailure { PatcherLog.error(it) { "$name not found" } }.getOrNull()
    val extractor = MediaExtractor(
        classLoader,
        optional("Media image getter") { ::mediaImageInfoGetter.method },
        optional("Media video getter") { ::mediaVideoVersionsGetter.method },
        optional("Media carousel getter") { ::mediaCarouselGetter.method },
        optional("Video width getter") { ::videoVersionWidthGetter.method },
        optional("Video height getter") { ::videoVersionHeightGetter.method },
        ::log,
    )
    val dialog = InstagramDialog(
        optional("Dialog builder") { ::igDialogBuildMethod.method },
        optional("Dialog title") { ::igDialogTitleField.field },
        optional("Dialog cancelable") { ::igDialogCancelableMethod.method },
        optional("Dialog canceled on touch outside") { ::igDialogCanceledOnTouchOutsideMethod.method },
        optional("Dialog negative button") { ::igDialogNegativeButtonMethod.method },
        optional("Dialog menu corners") { ::igDialogMenuBottomCornersField.field },
        ::log,
    )
    val positionField = optional("Carousel position") { ::mediaPositionField.field.apply { isAccessible = true } }

    /**
     * The carousel position of the post state among [owners] or captured by them, read when the option is tapped so
     * it is the item on screen.
     */
    fun position(owners: List<Any?>): Int {
        val field = positionField ?: return 0
        val candidates = owners.filterNotNull()
        val state = candidates.firstOrNull { field.declaringClass.isInstance(it) }
            ?: candidates.firstNotNullOfOrNull { captured(it, field.declaringClass) }
            ?: return 0.also { log("no carousel position") }
        return field.getInt(state)
    }
    val icon = appContext.resources.getIdentifier("instagram_download_outline_24", "drawable", appContext.packageName)

    val highlightCover = optional("Highlight cover") { HighlightCover(classLoader, ::log) }

    /**
     * The option to download the cover of the highlight reachable from [owners], when a highlight is shown.
     */
    fun highlightCoverOptions(activity: Activity, owners: List<Any?>): List<Pair<String, () -> Unit>> {
        val url = runCatching { highlightCover?.url(owners) }
            .onFailure { log("highlight cover failed: $it") }.getOrNull() ?: return emptyList()
        return listOf(HIGHLIGHT_COVER_LABEL to { enqueue(activity, listOf(MediaExtractor.Item(url, false, url))) })
    }

    fun downloadMedia(
        activity: Activity,
        media: Any?,
        owners: List<Any?> = emptyList(),
        storyOwners: List<Any?> = emptyList(),
    ) {
        try {
            if (media == null) {
                log("no media to download")
                return toast(activity, "No media found")
            }
            val items = extractor.items(media)
            val position = if (items.size > 1) position(owners) else 0
            log("${items.size} items, at $position")
            download(dialog, activity, items, position, highlightCoverOptions(activity, storyOwners))
        } catch (e: Exception) {
            PatcherLog.error(e) { "Could not download the media" }
            log("failed: ${e.stackTraceToString().take(1500)}")
            toast(activity, "Download failed")
        }
    }

    val menus = mutableMapOf<String, Result<Unit>>()

    // The full overflow menu of posts, built from MediaOption$Option values.
    menus["post menu"] = runCatching {
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
                    addOption.invoke(null, style, option, creator, DOWNLOAD_LABEL, added, false)
                    // Like Piko's, the option is the first of the menu.
                    if (added.isNotEmpty() && options.none { it in added }) {
                        options.addAll(0, added)
                        log("added to the post menu")
                    }
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download option to the post menu" }
                }
            }
        }

        ::overflowMenuOptionClickMethod.hookMethod {
            before { param ->
                if (param.args[0] !== option) return@before
                param.result = null
                log("tapped in the post menu")
                val helper = param.thisObject
                val activity = activityIn(helper) ?: return@before log("no activity in the post menu")
                // The menu reads its post with a static getter of the helper.
                val media = runCatching {
                    helper.javaClass.declaredMethods.first { method ->
                        Modifier.isStatic(method.modifiers) && method.returnType == mediaClass &&
                            method.parameterTypes.contentEquals(arrayOf(helper.javaClass))
                    }.apply { isAccessible = true }.invoke(null, helper)
                }.onFailure { PatcherLog.error(it) { "Could not read the post of the menu" } }.getOrNull()
                downloadMedia(activity, media, listOf(helper))
            }
        }
    }

    // The bottom sheet menus of reels, of posts shown like reels and of the "≡" button of posts in the home feed.
    // Like Piko's reels button, the item is added to the sheet builder right after the menu creates it.
    menus["sheet menus"] = runCatching {
        val builderClass = ::sheetMenuBuilderClass.clazz
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
                val module = param.args.firstOrNull { it in SHEET_MENU_MODULES } ?: return@after
                // Once per sheet, even when menu methods call each other.
                if (!handledBuilders.add(param.thisObject)) return@after
                try {
                    val owners = listOfNotNull(outerParam.thisObject) + outerParam.args.filterNotNull()
                    val media = owners.firstOrNull { mediaClass.isInstance(it) }
                        ?: owners.firstNotNullOfOrNull { captured(it, mediaClass) }
                        ?: return@after log("no post for $module")
                    val activity = owners.firstNotNullOfOrNull(::activityIn)
                        ?: return@after log("no activity for $module")
                    val onClick = View.OnClickListener {
                        log("tapped in $module")
                        downloadMedia(activity, media, owners)
                    }
                    addItem.invoke(param.thisObject, activity, onClick, DOWNLOAD_LABEL, icon, false)
                    log("added to $module")
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download option to $module" }
                }
            }
        }
        val builderConstructors = builderClass.declaredConstructors.map { it to addDownload }.toTypedArray()
        val menuMethods = ::sheetMenuMethods.dexMethodList.ifEmpty { throw Exception("Sheet menus not found") }
        menuMethods.forEach { it.hookMethod(scopedHook(*builderConstructors)) }
        log("hooked ${menuMethods.size} sheet menus")
    }

    // The "⋯" menu of other people's stories. Like Piko, a label is added to the labels of the
    // "ReelOptionsOverflowHelper", which become the rows of the menu, and handled by its label click handlers.
    val reelItemClass = classLoader.loadClass("com.instagram.model.reels.ReelItem")
    fun storyMedia(reelItem: Any?): Any? = reelItem?.let { item ->
        // The main Media of a ReelItem is its last Media field, like in Piko.
        item.javaClass.declaredFields.lastOrNull { it.type == mediaClass }?.apply { isAccessible = true }?.get(item)
    }

    menus["story menu"] = runCatching {
        val labelsMethod = ::storyMenuLabelsMethod.method
        val helperClass = labelsMethod.declaringClass
        fun fieldOf(type: Class<*>) = helperClass.declaredFields.first {
            !Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val activityField = fieldOf(Activity::class.java)
        val reelItemField = fieldOf(reelItemClass)

        labelsMethod.hookMethod {
            after { param ->
                val labels = param.result as? Array<*> ?: return@after
                if (labels.any { it?.toString() == DOWNLOAD_LABEL }) return@after
                // Like Piko's, the label is the first of the menu.
                @Suppress("UNCHECKED_CAST")
                val withDownload = java.lang.reflect.Array.newInstance(CharSequence::class.java, labels.size + 1)
                    as Array<CharSequence?>
                withDownload[0] = DOWNLOAD_LABEL
                labels.forEachIndexed { index, label -> withDownload[index + 1] = label as CharSequence? }
                param.result = withDownload
                log("added to the story menu")
            }
        }

        // The icon of a label: icon(helper, CharSequence label).
        helperClass.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Int::class.java &&
                method.parameterTypes.contentEquals(arrayOf(helperClass, CharSequence::class.java))
        }.forEach { method ->
            method.hookMethod {
                after { param -> if (param.args[1]?.toString() == DOWNLOAD_LABEL) param.result = icon }
            }
        }

        // The label click handlers: static void methods taking the helper and the label as last parameter.
        val clickHandlers = helperClass.declaredMethods.filter { method ->
            val parameters = method.parameterTypes
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && parameters.isNotEmpty() &&
                parameters.last() == CharSequence::class.java && helperClass in parameters
        }.ifEmpty { throw Exception("Story menu click handlers not found") }
        clickHandlers.forEach { method ->
            method.hookMethod {
                before { param ->
                    if (param.args.last()?.toString() != DOWNLOAD_LABEL) return@before
                    param.result = null
                    log("tapped in the story menu")
                    val helper = param.args.first { helperClass.isInstance(it) }
                    val activity = activityField.get(helper) as Activity? ?: return@before log("no story activity")
                    downloadMedia(activity, storyMedia(reelItemField.get(helper)), storyOwners = listOf(helper))
                }
            }
        }
        log("hooked the story menu with ${clickHandlers.size} click handlers")
    }

    // Instagram can leave the options of the "ReelOptionsOverflowHelper" out of the story menu sheet, so a row is
    // also added to the rows the sheet is shown with, for the story on screen.
    menus["story menu rows"] = runCatching {
        val viewerClass = classLoader.loadClass("instagram.features.stories.fragment.ReelViewerFragment")
        val currentItem = viewerClass.declaredMethods.single {
            it.parameterCount == 0 && it.returnType == reelItemClass
        }.apply { isAccessible = true }
        var resumedViewer = WeakReference<Any>(null)
        viewerClass.getDeclaredMethod("onResume").hookMethod {
            after { param -> resumedViewer = WeakReference(param.thisObject) }
        }
        viewerClass.getDeclaredMethod("onPause").hookMethod {
            before { param -> if (resumedViewer.get() === param.thisObject) resumedViewer = WeakReference(null) }
        }

        val rowConstructor = ::storyMenuRowConstructor.constructor.apply { isAccessible = true }
        val function0 = rowConstructor.parameterTypes[7]
        ::storyMenuSheetMethod.hookMethod {
            before { param ->
                val viewer = resumedViewer.get() ?: return@before
                val rows = param.args[0] as? List<*> ?: return@before
                try {
                    val hasDownload = rows.any { row ->
                        row != null && row.javaClass.declaredFields.any { field ->
                            field.type == String::class.java &&
                                field.apply { isAccessible = true }.get(row) == DOWNLOAD_LABEL
                        }
                    }
                    if (hasDownload) return@before
                    val activity = viewer.javaClass.getMethod("getActivity").invoke(viewer) as Activity?
                        ?: return@before log("no activity for the story menu rows")
                    val reelItem = currentItem.invoke(viewer)
                    val media = storyMedia(reelItem)
                    val onClick = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, method, args ->
                        when (method.name) {
                            "invoke" -> {
                                log("tapped in the story menu rows")
                                downloadMedia(activity, media, storyOwners = listOf(viewer, reelItem))
                                Unit
                            }
                            "equals" -> proxy === args?.firstOrNull()
                            "hashCode" -> System.identityHashCode(proxy)
                            else -> "Download"
                        }
                    }
                    val row = rowConstructor.newInstance(
                        null, null, null, null, DOWNLOAD_LABEL, "", null, onClick, icon, false, false,
                    )
                    param.args[0] = listOf(row) + rows
                    log("added to the story menu rows")
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download row to the story menu" }
                }
            }
        }
    }

    // The menu of a long pressed highlight on a profile. Like the sheet menus, the item is added to the dialog builder
    // right after the menu creates it, so it is the first item.
    menus["highlight menu"] = runCatching {
        val addItem = ::highlightMenuAddMethod.method.apply { isAccessible = true }
        val cover = highlightCover ?: throw Exception("Highlight cover not available")
        val addCover: HookDsl<IScopedHookCallback>.() -> Unit = {
            after { param ->
                if (HIGHLIGHT_MENU_MODULE !in param.args) return@after
                try {
                    val helper = outerParam.thisObject
                    val owners = listOf(helper) + outerParam.args
                    val onClick = View.OnClickListener { view ->
                        log("tapped in the highlight menu")
                        try {
                            val activity = helper?.let(::activityIn) ?: activityIn(view)
                                ?: return@OnClickListener log("no activity for the highlight menu")
                            val url = cover.url(owners)
                                ?: return@OnClickListener toast(activity, "No highlight cover found")
                            enqueue(activity, listOf(MediaExtractor.Item(url, false, url)))
                        } catch (e: Exception) {
                            log("highlight cover failed: ${e.stackTraceToString().take(1500)}")
                            toast(view.context, "Download failed")
                        }
                    }
                    addItem.invoke(param.thisObject, HIGHLIGHT_COVER_LABEL, onClick)
                    log("added to the highlight menu")
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download option to the highlight menu" }
                }
            }
        }
        val builderConstructors =
            ::highlightMenuBuilderClass.clazz.declaredConstructors.map { it to addCover }.toTypedArray()
        ::highlightMenuMethod.method.hookMethod(scopedHook(*builderConstructors))
    }

    // The buttons below the expanded profile picture, a "Download" button is added after Instagram's.
    menus["profile picture"] = runCatching {
        val actionConstructor = ::profilePictureActionConstructor.constructor.apply { isAccessible = true }
        val function0 = actionConstructor.parameterTypes[1]
        val pictureIcon = appContext.resources.getIdentifier(
            "instagram_download_pano_outline_24", "drawable", appContext.packageName,
        ).takeIf { it != 0 } ?: icon

        // The URL is read from the User of the open profile, not the IgImageView: IgImageView no longer has
        // getUrlForViewTrackers on current Instagram, which disabled the whole option.
        val profileClass = classLoader.loadClass("com.instagram.profile.fragment.UserDetailFragment")
        val userClass = classLoader.loadClass("com.instagram.user.model.User")
        val profileUser = profileClass.declaredMethods.first { it.parameterCount == 0 && it.returnType == userClass }
            .apply { isAccessible = true }
        val imageUrlClass = classLoader.loadClass("com.instagram.common.typedurl.ImageUrl")
        val imageUrlGetUrl = imageUrlClass.getMethod("getUrl")
        // The no-argument getters of User returning an ImageUrl: the profile picture and its HD variant.
        val pictureGetters = userClass.methods
            .filter { it.parameterCount == 0 && imageUrlClass.isAssignableFrom(it.returnType) }
            .onEach { it.isAccessible = true }
        if (pictureGetters.isEmpty()) throw Exception("No profile picture getter on User")

        var resumedProfile = WeakReference<Any>(null)
        profileClass.getDeclaredMethod("onResume").hookMethod {
            after { param -> resumedProfile = WeakReference(param.thisObject) }
        }
        profileClass.getDeclaredMethod("onPause").hookMethod {
            before { param -> if (resumedProfile.get() === param.thisObject) resumedProfile = WeakReference(null) }
        }

        val imageUrlGetWidth = runCatching { imageUrlClass.getMethod("getWidth") }.getOrNull()
        val sizeInUrl = Regex("/s(\\d+)x\\d+/")
        // The pixel size of an ImageUrl: its width, or the size encoded in the CDN path (e.g. /s320x320/) when the
        // width getter is unavailable, so the largest profile picture variant can be picked.
        fun sizeOf(image: Any, url: String): Int {
            val width = imageUrlGetWidth?.let { runCatching { it.invoke(image) as? Int }.getOrNull() } ?: 0
            val fromUrl = sizeInUrl.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return maxOf(width, fromUrl)
        }

        /**
         * The URL of the highest-resolution profile picture of the profile on screen. The User exposes several
         * ImageUrl getters (a small thumbnail and larger/HD variants); the widest is chosen so the download is not
         * low quality.
         */
        fun pictureUrl(): String? {
            val user = resumedProfile.get()?.let { profileUser.invoke(it) }
                ?: return null.also { log("no profile user") }
            var bestUrl: String? = null
            var bestSize = -1
            for (getter in pictureGetters) {
                val image = runCatching { getter.invoke(user) }.getOrNull() ?: continue
                val url = runCatching { imageUrlGetUrl.invoke(image) as? String }.getOrNull()
                if (url.isNullOrEmpty()) continue
                val size = sizeOf(image, url)
                if (size > bestSize) {
                    bestSize = size
                    bestUrl = url
                }
            }
            if (bestUrl == null) log("no profile picture url on the user")
            return bestUrl
        }

        val pictureId = appContext.resources.getIdentifier("expanded_profile_pic", "id", appContext.packageName)

        fun downloadPicture(view: View) {
            try {
                val activity = activityIn(view)
                val url = pictureUrl()
                when {
                    activity == null -> log("no activity for the profile picture")
                    url == null -> toast(activity, "No profile picture found")
                    else -> enqueue(activity, listOf(MediaExtractor.Item(url, false, url)))
                }
            } catch (e: Exception) {
                log("profile picture failed: ${e.stackTraceToString().take(1500)}")
                toast(view.context, "Download failed")
            }
        }

        // Long pressing the expanded profile picture downloads it, so the row of buttons below it (Follow, Share
        // profile, Copy link, QR code and Stealth) is not crowded further. A "Download" button is added to the row
        // only if the picture view cannot be found.
        ::profilePictureActionsMethod.hookMethod {
            before { param ->
                val bar = param.args[0] as? View ?: return@before
                val actions = param.args[2] as? List<*> ?: return@before
                try {
                    val picture = pictureId.takeIf { it != 0 }?.let { bar.rootView.findViewById<View>(it) }
                    if (picture != null) {
                        picture.setOnLongClickListener { view ->
                            log("long pressed the profile picture")
                            toast(view.context, "Downloading profile picture")
                            downloadPicture(view)
                            true
                        }
                        log("download on long press of the profile picture")
                        return@before
                    }
                    val onClick = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, method, args ->
                        when (method.name) {
                            "invoke" -> {
                                log("tapped below the profile picture")
                                downloadPicture(bar)
                                Unit
                            }
                            "equals" -> proxy === args?.firstOrNull()
                            "hashCode" -> System.identityHashCode(proxy)
                            else -> "Download profile picture"
                        }
                    }
                    val download = actionConstructor.newInstance(PROFILE_PICTURE_LABEL, onClick, pictureIcon, false)
                    param.args[2] = actions + download
                    log("added below the profile picture")
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not add the download option to the profile picture" }
                }
            }
        }
    }

    menus.forEach { (menu, result) ->
        result.onFailure { PatcherLog.error(it) { "No download option in the $menu" } }
    }
    if (menus.values.all { it.isFailure }) throw Exception("No menu found for the download option")
}
