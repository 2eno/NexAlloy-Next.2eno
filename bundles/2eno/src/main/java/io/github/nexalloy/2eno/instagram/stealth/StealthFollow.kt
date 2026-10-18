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
import io.github.nexalloy.`2eno`.instagram.friendshipStatusMapMethod
import io.github.nexalloy.`2eno`.instagram.jsonParserFromStringMethods
import io.github.nexalloy.`2eno`.instagram.profilePictureActionConstructor
import io.github.nexalloy.`2eno`.instagram.profilePictureActionsMethod
import io.github.nexalloy.`2eno`.instagram.reelResponseParserMethod
import io.github.nexalloy.`2eno`.instagram.reelStoreAddTrayReelsMethod
import io.github.nexalloy.`2eno`.instagram.tigonStartRequestMethods
import io.github.nexalloy.`2eno`.instagram.userIdGetterMethods
import io.github.nexalloy.`2eno`.instagram.userIsPrivateMethod
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

/** How soon a fetch is retried after it failed, instead of waiting the whole [REFRESH_INTERVAL]. */
private const val RETRY_INTERVAL = 30 * 1000L

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
 * Header names that mark a request as the signed-in REST API traffic whose headers the patch reuses. "X-IG-WWW-Claim"
 * is deliberately not here: it rides on Bloks requests too, which do not carry the Authorization/Cookie the patch's
 * own reels_media request needs, so keying off it captured the wrong request.
 */
private val AUTH_HEADERS = setOf("authorization", "cookie", "ig-u-ds-user-id")

/** Class names of a request URL, read to tell the REST API requests apart from Bloks and other traffic. */
private val URL_TYPES = setOf("java.net.URI", "java.net.URL", "android.net.Uri")

/**
 * The headers Instagram sends with its signed-in REST API requests, so the patch can fetch stories as that account.
 */
private object ApiHeaders {
    @Volatile
    var headers: Map<String, String> = emptyMap()
        private set

    // The size of the fullest header set kept so far, and whether it carried an auth header, so a later, sparser or
    // unauthenticated request does not replace a good capture.
    @Volatile
    private var captured = 0

    @Volatile
    private var haveAuth = false

    private val objectFields = ConcurrentHashMap<Class<*>, List<Field>>()

    // Endpoints already logged, so each request path's auth flags are reported once rather than on every request.
    private val loggedPaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * Reads the headers of a request among [args] (the `TigonServiceLayer.startRequest` request). Headers can be a
     * `Map<String, String>` field, a list of `Name: value` / map-entry header objects, or a bare Authorization/Cookie
     * string field, across the request object, so every such field is merged. Each Instagram endpoint's auth flags
     * are logged once, to establish whether the login token is exposed in Java at all. The set carrying real auth is
     * kept if one is ever seen, else the fullest.
     */
    fun capture(args: Array<Any?>) {
        var url: String? = null
        val merged = LinkedHashMap<String, String>()
        for (arg in args) {
            arg ?: continue
            for (field in objectFields(arg.javaClass)) {
                when (val value = runCatching { field.get(arg) }.getOrNull()) {
                    null -> {}
                    is Map<*, *> -> for (e in value.entries) {
                        pair(e.key?.toString(), e.value?.toString())?.let { merged.putIfAbsent(it.first, it.second) }
                    }
                    is List<*> -> for (item in value) headerPair(item)?.let { merged.putIfAbsent(it.first, it.second) }
                    is CharSequence -> {
                        val text = value.toString()
                        when {
                            url == null && text.startsWith("http") && text.contains("instagram") -> url = text
                            text.startsWith("Bearer ") || text.contains("IGT:2:") -> merged.putIfAbsent("Authorization", text)
                            text.contains("sessionid=") -> merged.putIfAbsent("Cookie", text)
                        }
                    }
                    else -> if (url == null && value.javaClass.name in URL_TYPES) {
                        value.toString().takeIf { it.contains("instagram") }?.let { url = it }
                    }
                }
            }
        }
        if (url == null || !url!!.contains("instagram") || merged.isEmpty()) return
        val lower = merged.keys.map { it.lowercase() }.toSet()
        val auth = lower.any { it in AUTH_HEADERS }
        // Report each endpoint once with its auth flags, to tell whether Instagram exposes the login token in Java at
        // all (vs. adding it in the native network layer below this hook).
        val path = url!!.substringAfter("://").substringAfter('/').substringBefore('?').take(48)
        if (loggedPaths.add(path)) log(
            "request /$path: ${merged.size} headers " +
                "(authz=${"authorization" in lower}, cookie=${"cookie" in lower}, ua=${"user-agent" in lower})",
        )
        if (!auth && haveAuth) return
        if (auth == haveAuth && merged.size < captured) return
        val kept = merged.filterKeys { it.lowercase() !in SKIPPED_HEADERS }
        headers = kept
        captured = merged.size
        haveAuth = auth
    }

    /** A `Name: value` string, a `Name=value` string, or a map entry turned into a header pair. */
    private fun headerPair(header: Any?): Pair<String, String>? {
        header ?: return null
        if (header is Map.Entry<*, *>) return pair(header.key?.toString(), header.value?.toString())
        val text = header.toString()
        text.indexOf(": ").takeIf { it > 0 }?.let { return pair(text.substring(0, it), text.substring(it + 2)) }
        text.indexOf('=').takeIf { it > 0 }?.let { return pair(text.substring(0, it), text.substring(it + 1)) }
        return null
    }

    private fun pair(name: String?, value: String?): Pair<String, String>? =
        if (name.isNullOrEmpty() || value == null) null else name to value

    private fun objectFields(clazz: Class<*>) = objectFields.getOrPut(clazz) {
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
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
        var failed = false
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
                    failed = true
                    return@flatMap emptyList()
                }
                val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                // A requested reel can come back in the "reels_media" array, the "reels" map, or both, and which one
                // is populated varies by Instagram version, so read both (keeping one JSON per reel id) instead of
                // trusting whichever happens to be present. An empty "reels_media" array must not hide "reels".
                val jsons = LinkedHashMap<String, String>()
                body.optJSONArray("reels_media")?.let { array ->
                    for (i in 0 until array.length()) {
                        val reel = array.getJSONObject(i)
                        jsons[reel.optString("id", "reels_media:$i")] = reel.toString()
                    }
                }
                body.optJSONObject("reels")?.let { map ->
                    for (key in map.keys()) jsons.putIfAbsent(key, map.getJSONObject(key).toString())
                }
                jsons.values.mapNotNull { json ->
                    runCatching { parse(json) }.onFailure { log("could not parse a reel: $it") }.getOrNull()
                }
            } finally {
                connection.disconnect()
            }
        }
        // Keep the previously fetched stories when a fetch fails and returns nothing, rather than blanking the tray.
        if (!failed || fetched.isNotEmpty()) state.reels = fetched
        // On success cache for the full interval; after an error retry on the next tray update (in RETRY_INTERVAL)
        // instead of showing nothing until REFRESH_INTERVAL passes.
        state.fetchedAt =
            if (failed) System.currentTimeMillis() - REFRESH_INTERVAL + RETRY_INTERVAL else System.currentTimeMillis()
        log("fetched ${fetched.size} reels of ${ids.size} accounts" + if (failed) " (with errors, will retry)" else "")
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

    // The pk getters of User, found by the hash of their id column. The reliable source of the numeric user id;
    // empty when the fingerprint matched nothing on this build, where the heuristic below is the only source.
    val pkGetters = runCatching {
        ::userIdGetterMethods.dexMethodList.filter { it.isMethod }.map { it.toMethod().apply { isAccessible = true } }
    }.onFailure { PatcherLog.error(it) { "User id getters not found" } }.getOrNull().orEmpty()
    log("pk getters: ${pkGetters.size}")

    // The id getters of User are obfuscated and change between versions, so as a fallback the user id is read as the
    // value of a no-argument String getter that is all digits, which the numeric user id (pk) is. Sorted by name so
    // the order is stable across runs.
    val userIdGetters = userClass.methods
        .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        .sortedBy { it.name }
        .onEach { it.isAccessible = true }
    fun isUserId(value: String?) = value != null && value.length in 1..20 && value.all { it.isDigit() }
    // Resolved per user and re-validated every time, never cached across users: a getter that reads the pk for one
    // account can be unset or a different numeric field for another, which showed the wrong follow state on the
    // button and hid it on some profiles.
    fun userIdOf(user: Any): String? {
        fun read(getter: Method) = runCatching { getter.invoke(user) as? String }.getOrNull()
        for (getter in pkGetters) read(getter).takeIf(::isUserId)?.let { return it }
        for (getter in userIdGetters) read(getter).takeIf(::isUserId)?.let { return it }
        return null
    }

    // Whether the viewer already follows a user normally, read from the "following" entry of the friendship status
    // map. The button is hidden for such users, since their stories already come through the normal follow.
    val friendshipMap = runCatching { ::friendshipStatusMapMethod.method.apply { isAccessible = true } }
        .onFailure { PatcherLog.error(it) { "Friendship status map method not found" } }.getOrNull()
    val statusGetters = friendshipMap?.parameterTypes?.firstOrNull()?.let { statusClass ->
        userClass.methods.filter { it.parameterCount == 0 && it.returnType == statusClass }.onEach { it.isAccessible = true }
    }.orEmpty()
    fun alreadyFollowing(user: Any): Boolean {
        if (friendshipMap == null) return false
        for (getter in statusGetters) {
            val status = runCatching { getter.invoke(user) }.getOrNull() ?: continue
            val map = runCatching { friendshipMap.invoke(null, status) as? Map<*, *> }.getOrNull() ?: continue
            if (map["following"] == true) return true
        }
        return false
    }

    // Whether a profile is public. Stealth follow only works for public accounts, so the button is shown only for
    // those. When the private flag cannot be read, the account is treated as public rather than hiding the button.
    val isPrivateMethod = runCatching { ::userIsPrivateMethod.method.apply { isAccessible = true } }
        .onFailure { PatcherLog.error(it) { "User is-private method not found" } }.getOrNull()
    fun isPublic(user: Any): Boolean {
        val method = isPrivateMethod ?: return true
        val private = runCatching { method.invoke(user) as? Boolean }.getOrNull()
        return private == false
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
                // Only public accounts, whose stories can be fetched without following.
                if (!isPublic(user)) return@before
                // Hidden when the account is already followed normally; stealth follow would add nothing then.
                if (alreadyFollowing(user)) return@before
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
