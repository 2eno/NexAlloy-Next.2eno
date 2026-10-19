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
import io.github.nexalloy.`2eno`.instagram.reelStoreAddTrayReelsMethod
import io.github.nexalloy.`2eno`.instagram.userIdGetterMethods
import io.github.nexalloy.`2eno`.instagram.userIsPrivateMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

private const val PREFERENCES = "nexalloy_stealth_follow"
private const val ACCOUNTS = "accounts"

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

    // The story tray. This static method receives the reels Instagram parsed from one of its own authenticated
    // responses (the reel list is one of its arguments). When Instagram loads a stealth followed account's story
    // (opening the profile triggers that fetch), its reel passes through here and is captured; the captured reels are
    // then injected into later tray updates, so the story shows in the tray. Nothing is fetched by the patch: on
    // current Instagram the login token is added in a native layer the patch cannot read.
    val trayMethod = ::reelStoreAddTrayReelsMethod.method
    val trayIndex = trayMethod.parameterTypes.indexOfFirst { List::class.java.isAssignableFrom(it) }
    val capturedReels = ConcurrentHashMap<String, Any>()

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
                log("maybeAdd: ${presentIds.size} reels ${presentIds.take(8)}; cached ${capturedReels.keys}")
                // Inject captured stealth reels that are followed and not already in this list.
                val missing = ids.mapNotNull { id -> capturedReels[id]?.takeIf { id !in presentIds } }
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
