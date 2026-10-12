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
 * Piko, the video is the first video version. The image is the largest `ExtendedImageUrl` of the `ImageInfo`.
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
    private val videoWidthGetter: Method?,
    private val videoHeightGetter: Method?,
    private val log: (String) -> Unit,
) {
    constructor(
        classLoader: ClassLoader,
        imageInfoGetter: Method?,
        videoVersionsGetter: Method?,
        carouselGetter: Method?,
        videoWidthGetter: Method?,
        videoHeightGetter: Method?,
        log: (String) -> Unit,
    ) : this(
        classLoader.loadClass("com.instagram.feed.media.Media"),
        classLoader.loadClass("com.instagram.model.mediasize.ImageInfo"),
        classLoader.loadClass("com.instagram.model.mediasize.ExtendedImageUrl"),
        classLoader.loadClass("com.instagram.api.schemas.VideoVersionIntf"),
        imageInfoGetter?.apply { isAccessible = true },
        videoVersionsGetter?.apply { isAccessible = true },
        carouselGetter?.apply { isAccessible = true },
        videoWidthGetter?.apply { isAccessible = true },
        videoHeightGetter?.apply { isAccessible = true },
        log,
    )

    /**
     * A size of a photo or video, Piko's image and video variants.
     */
    class Variant(val url: String, val width: Int, val height: Int) {
        val label get() = if (width > 0 && height > 0) "${width}x$height" else "Unknown size"
    }

    /**
     * A photo or video, [imageUrl] is the photo or the cover image of the video.
     */
    class Item(
        val url: String,
        val isVideo: Boolean,
        val imageUrl: String?,
        val imageVariants: List<Variant> = emptyList(),
        val videoVariants: List<Variant> = emptyList(),
    )

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
        val videoVariants = versions.orEmpty().mapNotNull { version ->
            version ?: return@mapNotNull null
            val url = invoke(version, "getUrl") as? String ?: return@mapNotNull null
            Variant(url, get(videoWidthGetter, version) as? Int ?: 0, get(videoHeightGetter, version) as? Int ?: 0)
        }.distinctBy { it.url }

        val imageInfo = get(imageInfoGetter, media)
            ?: mediaData(media).let { data ->
                fields(data.javaClass).firstOrNull { imageInfoClass.isAssignableFrom(it.type) }?.get(data)
            }
        val imageVariants = imageInfo?.let(::imageVariants).orEmpty()
        val video = videoVariants.firstOrNull()?.url
        val image = imageVariants.firstOrNull()?.url
        if (video != null) return Item(video, isVideo = true, imageUrl = image, imageVariants, videoVariants)
        if (image == null) log("no video or image in ${media.javaClass.name} (versions ${versions?.size}, imageInfo $imageInfo)")
        return image?.let { Item(it, isVideo = false, imageUrl = it, imageVariants) }
    }

    /**
     * The sizes of the image, the largest first.
     */
    private fun imageVariants(imageInfo: Any): List<Variant> =
        imageInfoClass.methods
            .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
            .flatMap { method -> runCatching { method.invoke(imageInfo) as? List<*> }.getOrNull().orEmpty() }
            .filter { imageUrlClass.isInstance(it) }
            .mapNotNull { candidate ->
                val url = invoke(candidate!!, "getUrl") as? String ?: return@mapNotNull null
                Variant(url, invoke(candidate, "getWidth") as? Int ?: 0, invoke(candidate, "getHeight") as? Int ?: 0)
            }
            .distinctBy { it.url }
            .sortedByDescending { it.width.toLong() * it.height }

    private fun get(getter: Method?, owner: Any): Any? = getter?.let {
        runCatching { it.invoke(owner) }.onFailure { e -> log("${it.name} failed: $e") }.getOrNull()
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
