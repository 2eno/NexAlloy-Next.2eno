package io.github.nexalloy.piko.instagram.download

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the cover image of a story highlight. The cover is not one of the stories of the highlight, it is the
 * `cover_media` of the reel response (`ReelResponseItemIntf`) of the highlight shown, which the story viewer keeps a
 * few objects away: the story menu holds the viewer state, which holds the reel, which holds the reel response.
 * The objects are found by their types, so the obfuscated names do not matter.
 */
internal class HighlightCover(classLoader: ClassLoader, private val log: (String) -> Unit) {
    private val responseClass = classLoader.loadClass("com.instagram.model.reels.ReelResponseItemIntf")
    private val coverClass = classLoader.loadClass("com.instagram.api.schemas.ReelCoverMediaClientDictIntf")
    private val imageVersionClass =
        classLoader.loadClass("com.instagram.api.schemas.ReelCoverMediaImageVersionClientDictIntf")

    private val coverGetter = responseClass.methods.single { it.parameterCount == 0 && it.returnType == coverClass }
    private val imageVersionGetters = coverClass.methods.filter {
        it.parameterCount == 0 && it.returnType == imageVersionClass
    }
    private val getUrl = imageVersionClass.getMethod("getUrl")
    private val getWidth = imageVersionClass.getMethod("getWidth")
    private val getHeight = imageVersionClass.getMethod("getHeight")

    /**
     * The URL of the largest cover image of the highlight reachable from [roots], null when the story shown is not
     * in a highlight.
     */
    fun url(roots: List<Any?>): String? {
        val covers = responses(roots).mapNotNull { response -> runCatching { coverGetter.invoke(response) }.getOrNull() }
        val versions = covers.flatMap { cover ->
            imageVersionGetters.mapNotNull { getter -> runCatching { getter.invoke(cover) }.getOrNull() }
        }
        val largest = versions.maxByOrNull { version ->
            (getWidth.invoke(version) as Int).toLong() * (getHeight.invoke(version) as Int)
        } ?: return null.also { log("no highlight cover (${covers.size} covers)") }
        return getUrl.invoke(largest) as String?
    }

    /**
     * The reel responses reachable from [roots] through at most [MAX_DEPTH] fields of app classes.
     */
    private fun responses(roots: List<Any?>): List<Any> {
        val found = mutableListOf<Any>()
        val visited = IdentityHashMap<Any, Unit>()
        var level = roots.filterNotNull()
        for (depth in 0..MAX_DEPTH) {
            val next = mutableListOf<Any>()
            for (owner in level) {
                if (visited.size >= MAX_VISITED || visited.put(owner, Unit) != null) continue
                if (responseClass.isInstance(owner)) {
                    found += owner
                    continue
                }
                fields(owner.javaClass).forEach { field -> field.get(owner)?.let(next::add) }
            }
            level = next
        }
        return found
    }

    private val instanceFields = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * The fields of [clazz] and its superclasses that can lead to a reel response: app classes, not collections.
     */
    private fun fields(clazz: Class<*>): List<Field> = instanceFields.getOrPut(clazz) {
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java && !it.name.startsWith("android") && !it.name.startsWith("java") }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                val type = field.type
                !Modifier.isStatic(field.modifiers) && !type.isPrimitive && !type.isArray &&
                    (type.name.startsWith("X.") || type.name.startsWith("com.instagram.model.reels."))
            }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private companion object {
        const val MAX_DEPTH = 4
        const val MAX_VISITED = 5000
    }
}
