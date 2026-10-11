package io.github.nexalloy.piko.instagram.download

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the media URLs of an Instagram `Media` by type, so obfuscated names do not matter:
 *
 * - The media data is the field of `Media` whose type holds an `ImageInfo`, like Piko's `MediaData`.
 * - Its image candidates are the `ExtendedImageUrl`s of the lists of `ImageInfo`, the largest one is used.
 * - Its video versions are a list of `VideoVersionIntf`, the first one is used like in Piko.
 * - The items of a carousel are a list of `Media`.
 */
internal class MediaExtractor(
    private val mediaClass: Class<*>,
    private val imageInfoClass: Class<*>,
    private val imageUrlClass: Class<*>,
    private val videoVersionClass: Class<*>,
) {
    constructor(classLoader: ClassLoader) : this(
        classLoader.loadClass("com.instagram.feed.media.Media"),
        classLoader.loadClass("com.instagram.model.mediasize.ImageInfo"),
        classLoader.loadClass("com.instagram.model.mediasize.ExtendedImageUrl"),
        classLoader.loadClass("com.instagram.api.schemas.VideoVersionIntf"),
    )

    class Item(val url: String, val isVideo: Boolean)

    /**
     * The items of [media], one for each item of a carousel.
     */
    fun items(media: Any): List<Item> {
        val data = mediaData(media)
        val children = lists(data).firstOrNull { list -> list.firstOrNull()?.let(mediaClass::isInstance) == true }
        if (!children.isNullOrEmpty()) return children.mapNotNull { child -> child?.let(::item) }
        return listOfNotNull(item(media))
    }

    private fun item(media: Any): Item? {
        val data = mediaData(media)
        val video = lists(data).firstOrNull { list -> list.firstOrNull()?.let(videoVersionClass::isInstance) == true }
            ?.firstNotNullOfOrNull { version -> version?.let { invoke(it, "getUrl") as? String } }
        if (video != null) return Item(video, isVideo = true)
        return image(data)?.let { Item(it, isVideo = false) }
    }

    private fun image(data: Any): String? {
        val imageInfo = fields(data.javaClass).firstOrNull { imageInfoClass.isAssignableFrom(it.type) }?.get(data)
            ?: return null
        val candidates = imageInfoClass.methods
            .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
            .flatMap { method -> runCatching { method.invoke(imageInfo) as? List<*> }.getOrNull().orEmpty() }
            .filter { imageUrlClass.isInstance(it) }
        val largest = candidates.maxByOrNull { candidate ->
            val width = invoke(candidate!!, "getWidth") as? Int ?: 0
            val height = invoke(candidate, "getHeight") as? Int ?: 0
            width.toLong() * height
        } ?: return null
        return invoke(largest, "getUrl") as? String
    }

    /**
     * The media data of [media], or [media] itself on versions keeping the data in `Media`.
     */
    private fun mediaData(media: Any): Any {
        val field = mediaDataFields.getOrPut(media.javaClass) {
            listOfNotNull(
                fields(media.javaClass).firstOrNull { field ->
                    fields(field.type).any { imageInfoClass.isAssignableFrom(it.type) }
                }
            )
        }.firstOrNull()
        return field?.get(media) ?: media
    }

    private fun lists(owner: Any): List<List<*>> =
        fields(owner.javaClass).filter { List::class.java.isAssignableFrom(it.type) }
            .mapNotNull { it.get(owner) as? List<*> }
            .filter { it.isNotEmpty() }

    private val mediaDataFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val instanceFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

    private fun fields(clazz: Class<*>): List<Field> = instanceFields.getOrPut(clazz) {
        clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
    }

    private fun invoke(owner: Any, name: String): Any? =
        methods.getOrPut(owner.javaClass to name) { owner.javaClass.getMethod(name) }.invoke(owner)
}
