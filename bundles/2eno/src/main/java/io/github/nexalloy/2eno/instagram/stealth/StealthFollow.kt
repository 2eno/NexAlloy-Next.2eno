package io.github.nexalloy.`2eno`.instagram.stealth

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.`2eno`.instagram.jsonParserFromStringMethods
import io.github.nexalloy.`2eno`.instagram.profilePictureActionConstructor
import io.github.nexalloy.`2eno`.instagram.profilePictureActionsMethod
import io.github.nexalloy.`2eno`.instagram.reelResponseParserMethod
import io.github.nexalloy.`2eno`.instagram.reelStoreAddTrayReelsMethod
import io.github.nexalloy.`2eno`.instagram.tigonStartRequestMethods
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val PREFERENCES = "nexalloy_stealth_follow"
private const val ACCOUNTS = "accounts"
private const val API = "https://i.instagram.com/api/v1/"

/** How long fetched stories are reused before they are fetched again. */
private const val REFRESH_INTERVAL = 5 * 60 * 1000L

/** Reels requested in one call. */
private const val BATCH_SIZE = 20

/** Headers of Instagram's requests that are not copied to the requests of the patch. */
private val SKIPPED_HEADERS = setOf("host", "content-length", "content-type", "accept-encoding", "connection")

private const val FOLLOW_LABEL = "Stealth follow"
private const val FOLLOW_UNFOLLOW_LABEL = "Stealth unfollow"

private fun log(message: String) = Log.i("NexAlloy", "Stealth follow: $message").let { }

/**
 * Updates the stealth follow button shown in [bar] to [label] and [icon], so a tap changes it at once instead of
 * only on the next time the profile picture is opened. The row of the button is the one whose label is one of the
 * stealth labels.
 */
private fun updateStealthButton(bar: View, label: String, icon: Int) {
    fun forEachView(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) forEachView(view.getChildAt(i), action)
    }
    forEachView(bar) { view ->
        if (view is TextView && view.text?.toString() in setOf(FOLLOW_LABEL, FOLLOW_UNFOLLOW_LABEL)) {
            view.text = label
            (view.parent as? ViewGroup)?.let { row ->
                for (i in 0 until row.childCount) {
                    (row.getChildAt(i) as? ImageView)?.let { if (icon != 0) it.setImageResource(icon) }
                }
            }
        }
    }
}

/**
 * The public accounts followed only on this device, by user ID. Nothing is sent to Instagram, so these accounts do
 * not see a follow and gain no reach.
 */
private class StealthAccounts(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun ids(): Set<String> = preferences.getStringSet(ACCOUNTS, emptySet()).orEmpty()

    operator fun contains(id: String) = id in ids()

    /** Adds [id], or removes it when it is there. Returns whether it is followed now. */
    fun toggle(id: String): Boolean {
        val ids = ids().toMutableSet()
        val followed = if (id in ids) !ids.remove(id) else ids.add(id)
        preferences.edit().putStringSet(ACCOUNTS, ids).apply()
        return followed
    }
}

/**
 * The headers Instagram sends with its API requests, so the patch can fetch stories as the signed-in account.
 */
private object ApiHeaders {
    @Volatile
    var headers: Map<String, String> = emptyMap()
        private set

    private val listFields = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * Reads the headers of the `TigonServiceLayer.startRequest` request among [args]. A header is an object whose
     * `toString` is `Name: value`, kept in a list of the request.
     */
    fun capture(args: Array<Any?>) {
        for (request in args) {
            request ?: continue
            for (field in listFields(request.javaClass)) {
                val list = field.get(request) as? List<*> ?: continue
                val pairs = list.mapNotNull { header ->
                    val text = header?.toString() ?: return@mapNotNull null
                    val separator = text.indexOf(": ")
                    if (separator <= 0) null else text.substring(0, separator) to text.substring(separator + 2)
                }
                if (pairs.none { it.first.equals("Authorization", ignoreCase = true) }) continue
                headers = pairs.filter { it.first.lowercase() !in SKIPPED_HEADERS }.toMap()
                return
            }
        }
    }

    private fun listFields(clazz: Class<*>) = listFields.getOrPut(clazz) {
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .toList()
    }
}

/**
 * The fetched stories of the stealth followed accounts.
 */
private class StealthReels {
    @Volatile
    var reels: List<Any> = emptyList()

    @Volatile
    var fetchedAt = 0L

    @Volatile
    var fetching = false
}

val StealthFollow = patch(
    name = "Stealth follow",
    description = "Follow public accounts only on this device: their stories appear in your story tray without " +
        "following them on Instagram, so you do not add to their reach. Long press a profile picture and tap " +
        "\"Stealth follow\".",
) {
    val accounts = StealthAccounts(appContext)
    val mainHandler = Handler(Looper.getMainLooper())
    val executor = Executors.newSingleThreadExecutor { Thread(it, "NexAlloy stealth follow") }
    fun toast(text: String) = mainHandler.post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }

    // The headers of Instagram's API requests, reused for the requests of the patch.
    ::tigonStartRequestMethods.dexMethodList.ifEmpty { throw Exception("TigonServiceLayer.startRequest not found") }
        .forEach { method ->
            method.hookMethod {
                before { param ->
                    try {
                        ApiHeaders.capture(param.args)
                    } catch (e: Exception) {
                        PatcherLog.error(e) { "Could not read the request headers" }
                    }
                }
            }
        }

    // Instagram's own parser turns the JSON of the API into reels.
    val parseReel: Method = ::reelResponseParserMethod.method.apply { isAccessible = true }
    val reelParser = parseReel.declaringClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    val jsonParsers = ::jsonParserFromStringMethods.dexMethodList
        .filter { it.isMethod }
        .map { it.toMethod().apply { isAccessible = true } }
    if (jsonParsers.isEmpty()) throw Exception("JSON parser factory not found")
    val reelClass = classLoader.loadClass("com.instagram.model.reels.ReelResponseItemIntf")
    val reelId = reelClass.getMethod("getId")

    fun parse(json: String): Any? {
        for (factory in jsonParsers) {
            val reel = runCatching { parseReel.invoke(reelParser, factory.invoke(null, json)) }.getOrNull()
            if (reel != null) return reel
        }
        return null
    }

    val state = StealthReels()

    fun fetch(ids: Set<String>) {
        val headers = ApiHeaders.headers
        if (headers.isEmpty()) return log("no request headers yet")
        val fetched = ids.chunked(BATCH_SIZE).flatMap { batch ->
            val url = API + "feed/reels_media/?" + batch.joinToString("&") { "reel_ids=$it" }
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    log("reels_media returned HTTP $code")
                    return@flatMap emptyList()
                }
                val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val jsons = body.optJSONArray("reels_media")?.let { array ->
                    (0 until array.length()).map { array.getJSONObject(it).toString() }
                } ?: body.optJSONObject("reels")?.let { map ->
                    map.keys().asSequence().map { map.getJSONObject(it).toString() }.toList()
                } ?: emptyList()
                jsons.mapNotNull { json ->
                    runCatching { parse(json) }.onFailure { log("could not parse a reel: $it") }.getOrNull()
                }
            } finally {
                connection.disconnect()
            }
        }
        state.reels = fetched
        state.fetchedAt = System.currentTimeMillis()
        log("fetched ${fetched.size} reels of ${ids.size} accounts")
    }

    fun refresh(force: Boolean = false) {
        val ids = accounts.ids()
        if (ids.isEmpty() || state.fetching) return
        if (!force && System.currentTimeMillis() - state.fetchedAt < REFRESH_INTERVAL) return
        state.fetching = true
        executor.execute {
            try {
                fetch(ids)
            } catch (e: Exception) {
                log("fetching failed: ${e.stackTraceToString().take(1500)}")
            } finally {
                state.fetching = false
            }
        }
    }

    // The story tray: the stories of the stealth followed accounts come first, unless the tray already has them.
    val trayMethod = ::reelStoreAddTrayReelsMethod.method
    val trayIndex = trayMethod.parameterTypes.indexOfFirst { List::class.java.isAssignableFrom(it) }
    trayMethod.hookMethod {
        before { param ->
            try {
                val ids = accounts.ids()
                if (ids.isEmpty() || trayIndex < 0) return@before
                refresh()
                val tray = param.args.getOrNull(trayIndex) as? List<*> ?: return@before
                val trayIds = tray.mapNotNull { reel ->
                    reel?.takeIf(reelClass::isInstance)?.let { reelId.invoke(it) as? String }
                }.toSet()
                val added = state.reels.filter { reel ->
                    val id = reelId.invoke(reel) as? String
                    id != null && id in ids && id !in trayIds
                }
                if (added.isEmpty()) return@before
                @Suppress("UNCHECKED_CAST")
                param.args[trayIndex] = added + (tray as List<Any?>)
                log("added ${added.size} reels to the story tray")
            } catch (e: Exception) {
                PatcherLog.error(e) { "Could not add the stealth followed stories" }
            }
        }
    }

    // The button below the expanded profile picture, after Follow, Share profile, Copy link and QR code. Wrapped so
    // a failure here still leaves the story tray injection working.
    runCatching {
    val profileClass = classLoader.loadClass("com.instagram.profile.fragment.UserDetailFragment")
    val userClass = classLoader.loadClass("com.instagram.user.model.User")
    val profileUser = profileClass.declaredMethods.first { it.parameterCount == 0 && it.returnType == userClass }
        .apply { isAccessible = true }

    // The id getters of User are obfuscated and change between versions, so the user id is read as the value of the
    // single no-argument String getter that is all digits, which only the numeric user id (pk) is.
    val userIdGetters = userClass.methods
        .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        .onEach { it.isAccessible = true }
    val chosenUserIdGetter = java.util.concurrent.atomic.AtomicReference<Method>()
    fun userIdOf(user: Any): String? {
        fun read(getter: Method) = runCatching { getter.invoke(user) as? String }.getOrNull()
        chosenUserIdGetter.get()?.let { return read(it) }
        for (getter in userIdGetters) {
            val value = read(getter)
            if (value != null && value.length in 1..20 && value.all { it.isDigit() }) {
                chosenUserIdGetter.set(getter)
                return value
            }
        }
        return null
    }

    var resumedProfile = WeakReference<Any>(null)
    profileClass.getDeclaredMethod("onResume").hookMethod {
        after { param -> resumedProfile = WeakReference(param.thisObject) }
    }
    profileClass.getDeclaredMethod("onPause").hookMethod {
        before { param -> if (resumedProfile.get() === param.thisObject) resumedProfile = WeakReference(null) }
    }

    val actionConstructor = ::profilePictureActionConstructor.constructor.apply { isAccessible = true }
    val function0 = actionConstructor.parameterTypes[1]
    fun drawable(name: String) = appContext.resources.getIdentifier(name, "drawable", appContext.packageName)
    // Following shows the crossed-out eye ("unfollow"), not following the open eye ("follow").
    val iconFollow = drawable("instagram_eye_pano_outline_24")
    val iconUnfollow = drawable("instagram_eye_off_pano_outline_24")
    fun label(following: Boolean) = if (following) FOLLOW_UNFOLLOW_LABEL else FOLLOW_LABEL
    fun iconOf(following: Boolean) = if (following) iconUnfollow else iconFollow

    ::profilePictureActionsMethod.hookMethod {
        before { param ->
            try {
                val profile = resumedProfile.get() ?: return@before
                val actions = param.args[2] as? List<*> ?: return@before
                val user = profileUser.invoke(profile) ?: return@before
                val id = userIdOf(user) ?: return@before
                val bar = param.args[0] as? View
                val onClick = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, method, args ->
                    when (method.name) {
                        "invoke" -> {
                            try {
                                val followed = accounts.toggle(id)
                                log("${if (followed) "followed" else "unfollowed"} $id")
                                // Change the button's own icon and label at once, so the tap is visible.
                                bar?.let { updateStealthButton(it, label(followed), iconOf(followed)) }
                                if (followed) {
                                    toast("Stealth following. Refresh your feed to see their stories.")
                                    refresh(force = true)
                                } else {
                                    state.reels = state.reels.filter { reelId.invoke(it) != id }
                                    toast("Stealth unfollowed")
                                }
                            } catch (e: Exception) {
                                log("toggling failed: ${e.stackTraceToString().take(1500)}")
                            }
                            Unit
                        }
                        "equals" -> proxy === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        else -> label(id in accounts)
                    }
                }
                val following = id in accounts
                @Suppress("UNCHECKED_CAST")
                param.args[2] = (actions as List<Any?>) +
                    actionConstructor.newInstance(label(following), onClick, iconOf(following), false)
            } catch (e: Exception) {
                PatcherLog.error(e) { "Could not add the stealth follow button" }
            }
        }
    }
    }.onFailure { PatcherLog.error(it) { "Could not set up the stealth follow button" } }

    refresh(force = true)
}
