package io.github.nexalloy.piko.instagram.download

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the media URLs of an Instagram `Media`.
 *
 * Instagram 447 keeps many posts in a native live tree instead of the fields of `Media`, so the values are read with
 * the getters of `Media` ([imageInfoGetter], [videoVersionsGetter], [carouselGetter]), which read either. Like
 * Piko, the image is the largest `ExtendedImageUrl` of the `ImageInfo` and the video is the first video version.
 * Fields are only read when a getter returns nothing.
 */
internal class MediaExtractor(
    private val mediaClass: Class<*>,
    private val imageInfoClass: Class<*>,
    private val imageUrlClass: Class<*>,
    private val videoVersionClass: Class<*>,
    private val imageInfoGetter: Method?,
    private val videoVersionsGetter: Method?,
    private val carouselGetter: Method?,
    private val log: (String) -> Unit,
) {
    constructor(
        classLoader: ClassLoader,
        imageInfoGetter: Method?,
        videoVersionsGetter: Method?,
        carouselGetter: Method?,
        log: (String) -> Unit,
    ) : this(
        classLoader.loadClass("com.instagram.feed.media.Media"),
        classLoader.loadClass("com.instagram.model.mediasize.ImageInfo"),
        classLoader.loadClass("com.instagram.model.mediasize.ExtendedImageUrl"),
        classLoader.loadClass("com.instagram.api.schemas.VideoVersionIntf"),
        imageInfoGetter?.apply { isAccessible = true },
        videoVersionsGetter?.apply { isAccessible = true },
        carouselGetter?.apply { isAccessible = true },
        log,
    )

    /**
     * A photo or video, [imageUrl] is the photo or the cover image of the video.
     */
    class Item(val url: String, val isVideo: Boolean, val imageUrl: String?)

    /**
     * The items of [media], one for each item of a carousel.
     */
    fun items(media: Any): List<Item> {
        val children = get(carouselGetter, media) as? List<*>
            ?: lists(mediaData(media)).firstOrNull { list -> list.firstOrNull()?.let(mediaClass::isInstance) == true }
        if (!children.isNullOrEmpty()) {
            log("carousel of ${children.size} items")
            return children.mapNotNull { child -> child?.let(::item) }
        }
        return listOfNotNull(item(media))
    }

    private fun item(media: Any): Item? {
        val versions = get(videoVersionsGetter, media) as? List<*>
            ?: lists(mediaData(media)).firstOrNull { list ->
                list.firstOrNull()?.let(videoVersionClass::isInstance) == true
            }
        val video = versions?.firstNotNullOfOrNull { version -> version?.let { invoke(it, "getUrl") as? String } }

        val imageInfo = get(imageInfoGetter, media)
            ?: mediaData(media).let { data ->
                fields(data.javaClass).firstOrNull { imageInfoClass.isAssignableFrom(it.type) }?.get(data)
            }
        val image = imageInfo?.let(::largestImage)
        if (video != null) return Item(video, isVideo = true, imageUrl = image)
        if (image == null) log("no video or image in ${media.javaClass.name} (versions ${versions?.size}, imageInfo $imageInfo)")
        return image?.let { Item(it, isVideo = false, imageUrl = it) }
    }

    private fun largestImage(imageInfo: Any): String? {
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

    private fun get(getter: Method?, media: Any): Any? = getter?.let {
        runCatching { it.invoke(media) }.onFailure { e -> log("${it.name} failed: $e") }.getOrNull()
    }

    /**
     * The media data of [media], the field of `Media` whose type holds an `ImageInfo`, or [media] itself.
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
