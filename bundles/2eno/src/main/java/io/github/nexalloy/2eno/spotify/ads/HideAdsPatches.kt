package io.github.nexalloy.`2eno`.spotify.ads

import android.media.MediaMetadata
import android.media.session.MediaSession
import app.twoeno.extension.spotify.BlockPopupAdsPatch
import app.twoeno.extension.spotify.HideAdSectionsPatch
import app.twoeno.extension.spotify.HideContextMenuUpsellsPatch
import app.twoeno.extension.spotify.HidePlayerAdsPatch
import app.twoeno.extension.spotify.MuteAudioAdsPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.`2eno`.spotify.browseSectionsGetters
import io.github.nexalloy.`2eno`.spotify.casitaHomeSectionsGetters
import io.github.nexalloy.`2eno`.spotify.contextMenuViewModelClass
import io.github.nexalloy.`2eno`.spotify.embeddedAdPlaylistPropertiesConstructor
import io.github.nexalloy.`2eno`.spotify.homeSectionsGetters
import io.github.nexalloy.`2eno`.spotify.npvPluginEntryConstructors
import io.github.nexalloy.`2eno`.spotify.pendragonRequestMethods
import io.github.nexalloy.`2eno`.spotify.premiumUpsellField
import java.lang.reflect.Constructor

val MuteAudioAds = patch(
    name = "Mute audio ads",
    description = "Mutes the music stream while an audio ad plays and restores the volume afterwards. " +
        "Enabling \"Device broadcast status\" in the Spotify settings improves the ad detection.",
) {
    MuteAudioAdsPatch.initialize(appContext)

    MediaSession::class.java.getMethod("setMetadata", MediaMetadata::class.java).hookMethod {
        after { MuteAudioAdsPatch.onMetadataChanged(it.args[0] as MediaMetadata?) }
    }
}

val HideAdSections = patch(
    name = "Hide ad sections",
    description = "Removes brand ad sections from the home and search page.",
) {
    val homeGetters = ::casitaHomeSectionsGetters.dexMethodList + ::homeSectionsGetters.dexMethodList
    val browseGetters = ::browseSectionsGetters.dexMethodList
    if (homeGetters.isEmpty() && browseGetters.isEmpty()) throw Exception("Page sections not found")

    homeGetters.forEach {
        it.hookMethod {
            after { param -> (param.result as? List<*>)?.let(HideAdSectionsPatch::removeHomeSections) }
        }
    }
    browseGetters.forEach {
        it.hookMethod {
            after { param -> (param.result as? List<*>)?.let(HideAdSectionsPatch::removeBrowseSections) }
        }
    }
}

val HideContextMenuUpsells = patch(
    name = "Hide context menu upsells",
    description = "Removes \"Premium\" entries from the context menus of songs, albums and playlists.",
) {
    val isPremiumUpsell = ::premiumUpsellField.field.apply { isAccessible = true }

    XposedBridge.hookAllConstructors(::contextMenuViewModelClass.clazz, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val parameterTypes = (param.method as Constructor<*>).parameterTypes
            parameterTypes.forEachIndexed { index, type ->
                if (type != List::class.java) return@forEachIndexed
                val items = param.args[index] as? List<*> ?: return@forEachIndexed
                param.args[index] = HideContextMenuUpsellsPatch.filterContextMenuItems(items, isPremiumUpsell)
            }
        }
    })
}

val BlockPopupAds = patch(
    name = "Block popup ads",
    description = "Blocks fullscreen promotions (\"Pendragon\" messages) shown when opening the app.",
) {
    val requests = ::pendragonRequestMethods.dexMethodList
    if (requests.isEmpty()) throw Exception("Pendragon message requests not found")

    requests.forEach {
        it.hookMethod {
            after { param -> param.result = BlockPopupAdsPatch.replaceRequest(param.result) }
        }
    }
}

val HidePlaylistAds = patch(
    name = "Hide playlist ads",
    description = "Removes ads embedded into playlists.",
) {
    ::embeddedAdPlaylistPropertiesConstructor.hookMethod {
        before { param ->
            // The first two parameters are the ad placement enums.
            val parameterTypes = (param.method as Constructor<*>).parameterTypes
            for (index in 0..1) {
                HidePlayerAdsPatch.noneOf(parameterTypes[index])?.let { param.args[index] = it }
            }
        }
    }
}

val HideVideoAds = patch(
    name = "Hide video ads",
    description = "Disables the video ad plugin of the now playing view.",
) {
    val entries = ::npvPluginEntryConstructors.dexMethodList
    if (entries.isEmpty()) throw Exception("Now playing view plugin entries not found")

    entries.forEach {
        it.hookMethod {
            before { param ->
                param.args[2] = HidePlayerAdsPatch.isPluginEnabled(param.args[0] as String?, param.args[2] as Boolean)
            }
        }
    }
}
