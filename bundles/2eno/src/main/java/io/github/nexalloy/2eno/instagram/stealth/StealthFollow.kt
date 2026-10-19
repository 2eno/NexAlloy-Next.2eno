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
import io.github.nexalloy.`2eno`.instagram.profilePictureActionConstructor
import io.github.nexalloy.`2eno`.instagram.profilePictureActionsMethod
import io.github.nexalloy.`2eno`.instagram.reelResponseParserMethod
import io.github.nexalloy.`2eno`.instagram.reelStoreAddTrayReelsMethod
import io.github.nexalloy.`2eno`.instagram.userIsPrivateMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val PREFERENCES = "nexalloy_stealth_follow"
private const val ACCOUNTS = "accounts"

// Single short words so the action row below the profile picture, which already has Follow, Share profile, Copy link
// and QR code, is not crowded further; the eye icon shows whether the account is stealth followed.
private const val FOLLOW_LABEL = "Stealth"
private const val FOLLOW_UNFOLLOW_LABEL = "Unstealth"

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

val StealthFollow = patch(
    name = "Stealth follow",
    description = "Follow public accounts only on this device: open their profile once and their story shows in " +
        "your story tray, without following them on Instagram, so you do not add to their reach. Tap a profile " +
        "picture and tap \"Stealth follow\".",
) {
    val accounts = StealthAccounts(appContext)
    val mainHandler = Handler(Looper.getMainLooper())
    fun toast(text: String) = mainHandler.post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }

    val reelClass = classLoader.loadClass("com.instagram.model.reels.ReelResponseItemIntf")
    val reelId = reelClass.getMethod("getId")
    val userClass = classLoader.loadClass("com.instagram.user.model.User")

    // The stored user id (pk) must equal a reel's id, since a user story's reel id is the owner's pk. The obfuscated
    // User getter that returns the pk is learned at runtime by matching a parsed reel's id to the user it belongs to,
    // so it needs no column hash or name; until then the heuristic (first all-digit String getter) is used.
    val userStringGetters = userClass.methods
        .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        .sortedBy { it.name }
        .onEach { it.isAccessible = true }
    val reelUserGetters = reelClass.methods
        .filter { it.parameterCount == 0 && it.returnType.name.contains("User") }
        .onEach { it.isAccessible = true }
    fun isUserId(value: String?) = value != null && value.length in 1..20 && value.all { it.isDigit() }
    val pkGetter = AtomicReference<Method>()
    fun calibratePk(reel: Any) {
        if (pkGetter.get() != null) return
        val rid = runCatching { reelId.invoke(reel) as? String }.getOrNull()?.takeIf(::isUserId) ?: return
        for (userGetter in reelUserGetters) {
            val user = runCatching { userGetter.invoke(reel) }.getOrNull()?.takeIf(userClass::isInstance) ?: continue
            for (getter in userStringGetters) {
                if (runCatching { getter.invoke(user) as? String }.getOrNull() == rid) {
                    pkGetter.set(getter)
                    log("calibrated pk getter: ${getter.name}")
                    return
                }
            }
        }
    }
    fun userIdOf(user: Any): String? {
        fun read(getter: Method) = runCatching { getter.invoke(user) as? String }.getOrNull()
        pkGetter.get()?.let { read(it).takeIf(::isUserId)?.let { v -> return v } }
        for (getter in userStringGetters) read(getter).takeIf(::isUserId)?.let { return it }
        return null
    }

    // The story tray. This static method receives the reels Instagram parsed from one of its own authenticated
    // responses (the reel list is one of its arguments). Stealth accounts' reels, captured below, are injected into
    // later tray updates so the story shows in the tray. Nothing is fetched by the patch: on current Instagram the
    // login token is added in a native layer the patch cannot read.
    val trayMethod = ::reelStoreAddTrayReelsMethod.method
    val trayIndex = trayMethod.parameterTypes.indexOfFirst { List::class.java.isAssignableFrom(it) }
    val capturedReels = ConcurrentHashMap<String, Any>()

    // Placeholder (stub) reels: for a stealth account whose story has not been captured yet, a copy of a real tray
    // reel is made with its id rewritten to the account's and its story items cleared, and injected into the tray so
    // Instagram prefetches that account's story itself (with its own auth); the real reel then arrives through the
    // parser and replaces the stub. Instagram's reel parser cannot build a reel from plain JSON on this version, so
    // the stub is copied from a reel Instagram built. It is used only if the copy really reports the new id and has
    // no items left, and given up after a few tray updates so a placeholder never lingers.
    val unsafe = runCatching {
        Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    }.getOrNull()
    val allocateInstance = unsafe?.javaClass?.getMethod("allocateInstance", Class::class.java)
    val instanceFields = ConcurrentHashMap<Class<*>, List<Field>>()
    fun fieldsOf(clazz: Class<*>) = instanceFields.getOrPut(clazz) {
        generateSequence(clazz) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()
    }
    val stubReels = ConcurrentHashMap<String, Any>()
    val stubTries = ConcurrentHashMap<String, Int>()
    val maxStubTries = 5
    fun stubReel(id: String, template: Any): Any? {
        if (allocateInstance == null || id == "0") return null
        val tries = stubTries.merge(id, 1) { a, b -> a + b } ?: 1
        if (tries > maxStubTries) {
            if (tries == maxStubTries + 1) log("gave up on a stub for $id: Instagram did not fetch the story")
            return null
        }
        stubReels[id]?.let { return it }
        val templateId = runCatching { reelId.invoke(template) as? String }.getOrNull() ?: return null
        return try {
            val copy = allocateInstance.invoke(unsafe, template.javaClass)!!
            for (field in fieldsOf(template.javaClass)) {
                val value = field.get(template)
                field.set(
                    copy,
                    when {
                        value is String && value == templateId -> id
                        // The template's story items must not show under the stealth account.
                        value is List<*> && value.isNotEmpty() ->
                            if (field.type.isAssignableFrom(ArrayList::class.java)) ArrayList<Any?>()
                            else throw IllegalStateException("cannot clear ${field.type.name} ${field.name}")
                        else -> value
                    },
                )
            }
            val copyId = runCatching { reelId.invoke(copy) as? String }.getOrNull()
            if (copyId != id) throw IllegalStateException("copy reports id $copyId")
            stubReels[id] = copy
            log("built stub reel $id from $templateId")
            copy
        } catch (e: Exception) {
            stubTries[id] = maxStubTries + 1
            log("could not build stub reel $id: $e")
            null
        }
    }

    // Capture: Instagram parses reels through this parser, including a profile's story it fetches with its own
    // authenticated request when the profile is opened. Each parsed reel calibrates the pk getter, and a stealth
    // account's reel (a ReelResponseItem, the type the tray holds) is kept for the tray hook to inject, since a
    // non-followed account's story never reaches the tray method itself.
    runCatching {
        ::reelResponseParserMethod.method.hookMethod {
            after { param ->
                try {
                    val reel = param.result?.takeIf(reelClass::isInstance) ?: return@after
                    calibratePk(reel)
                    val id = runCatching { reelId.invoke(reel) as? String }.getOrNull() ?: return@after
                    if (id in accounts.ids() && capturedReels.put(id, reel) == null) log("captured stealth reel $id")
                } catch (e: Exception) {
                    PatcherLog.error(e) { "Could not capture a parsed reel" }
                }
            }
        }
    }.onFailure { PatcherLog.error(it) { "Reel parser hook not set up" } }

    trayMethod.hookMethod {
        before { param ->
            try {
                val ids = accounts.ids()
                if (ids.isEmpty() || trayIndex < 0) return@before
                val reels = param.args.getOrNull(trayIndex) as? List<*> ?: return@before
                fun idOf(reel: Any?) =
                    reel?.takeIf(reelClass::isInstance)?.let { runCatching { reelId.invoke(it) as? String }.getOrNull() }
                val presentIds = reels.mapNotNull(::idOf)
                // Capture the reels of stealth accounts that Instagram itself loaded and passed through here.
                reels.forEach { reel ->
                    val id = idOf(reel) ?: return@forEach
                    if (id in ids && reel != null && capturedReels.put(id, reel) == null) log("captured stealth reel $id")
                }
                // Inject each followed stealth account not already in this list: the captured real reel if we have
                // it, otherwise a stub so Instagram prefetches the story itself and the real reel arrives later.
                val template = reels.firstOrNull { idOf(it)?.all(Char::isDigit) == true }
                val missing = ids.filter { it !in presentIds }.mapNotNull { id ->
                    capturedReels[id] ?: template?.let { stubReel(id, it) }
                }
                if (missing.isEmpty()) return@before
                @Suppress("UNCHECKED_CAST")
                param.args[trayIndex] = missing + (reels as List<Any?>)
                log("injected ${missing.size} stealth reels into the tray")
            } catch (e: Exception) {
                PatcherLog.error(e) { "Could not add the stealth followed stories" }
            }
        }
    }

    // The button below the expanded profile picture, after Follow, Share profile, Copy link and QR code. Wrapped so
    // a failure here still leaves the story tray injection working.
    runCatching {
    val profileClass = classLoader.loadClass("com.instagram.profile.fragment.UserDetailFragment")
    val profileUser = profileClass.declaredMethods.first { it.parameterCount == 0 && it.returnType == userClass }
        .apply { isAccessible = true }

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
                // Only public accounts, whose stories can be seen without following.
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
                                toast(
                                    if (followed) "Stealth following. Open their profile to load their story."
                                    else "Stealth unfollowed",
                                )
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
}
