package io.github.nexalloy.`2eno`.spotify.privacy

import android.content.ClipData
import app.twoeno.extension.spotify.SanitizeSharingLinksPatch
import io.github.nexalloy.patch
import io.github.nexalloy.`2eno`.spotify.formatAndroidShareSheetUrlMethod
import io.github.nexalloy.`2eno`.spotify.shareCopyUrlMethod
import io.github.nexalloy.scopedHook

val SanitizeSharingLinks = patch(
    name = "Sanitize sharing links",
    description = "Removes the tracking parameters (si, utm_source) from shared and copied links.",
) {
    // "Copy link": sanitize the clip created while copying.
    val newPlainText = ClipData::class.java.getMethod(
        "newPlainText", CharSequence::class.java, CharSequence::class.java
    )
    ::shareCopyUrlMethod.hookMethod(scopedHook(newPlainText) {
        before { param ->
            (param.args[1] as? CharSequence)?.let { param.args[1] = SanitizeSharingLinksPatch.sanitizeText(it) }
        }
    })

    // Share sheet: sanitize the url parameter of the share message formatter.
    ::formatAndroidShareSheetUrlMethod.hookMethod {
        before { param ->
            (param.args[1] as? String)?.let { param.args[1] = SanitizeSharingLinksPatch.sanitizeUrl(it) }
        }
    }
}
