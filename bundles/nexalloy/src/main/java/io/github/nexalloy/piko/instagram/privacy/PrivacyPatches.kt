package io.github.nexalloy.piko.instagram.privacy

import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodReplacement.DO_NOTHING
import io.github.nexalloy.patch
import io.github.nexalloy.piko.instagram.directComposerOnTextChangedMethod
import io.github.nexalloy.piko.instagram.directScreenshotCaptureMethod
import io.github.nexalloy.piko.instagram.markThreadSeenMethod
import io.github.nexalloy.piko.instagram.screenshotDetectorMethod
import io.github.nexalloy.piko.instagram.screenshotObserverStartMethod
import io.github.nexalloy.piko.instagram.secureWindowFlagsMethod
import io.github.nexalloy.piko.instagram.shared.InterceptRequests
import io.github.nexalloy.piko.instagram.shared.RequestFilter
import io.github.nexalloy.piko.instagram.storySeenStateMethod
import io.github.nexalloy.scopedHook
import java.lang.reflect.Method

val ViewStoriesAnonymously = patch(
    name = "View stories anonymously",
    description = "View stories without appearing in the viewer list.",
    use = false,
) {
    dependsOn(InterceptRequests)
    RequestFilter.enable(RequestFilter.Rule.STORY_SEEN)

    // Mark the stories as seen locally, although the server is not told.
    ::storySeenStateMethod.hookMethod {
        after { it.result = true }
    }
}

val ViewDmsAnonymously = patch(
    name = "View DMs anonymously",
    description = "Read messages without sending read receipts.",
    use = false,
) {
    ::markThreadSeenMethod.hookMethod(DO_NOTHING)
}

val DisableTypingStatus = patch(
    name = "Disable typing status",
    description = "Stops showing others that you are typing a message.",
    use = false,
) {
    ::directComposerOnTextChangedMethod.hookMethod(DO_NOTHING)
}

val DisableScreenshotDetection = patch(
    name = "Disable screenshot detection",
    description = "Disables the screenshot detection and allows screenshots of view once media in DMs.",
    use = false,
) {
    // Do not observe the screenshot directory.
    val observerStart = ::screenshotObserverStartMethod.member
    val skippedResult = when ((observerStart as? Method)?.returnType) {
        Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        else -> throw Exception("Unexpected screenshot observer $observerStart")
    }
    ::screenshotDetectorMethod.hookMethod(scopedHook(observerStart) {
        before { it.result = skippedResult }
    })

    // Do not notify about screenshots.
    ::directScreenshotCaptureMethod.hookMethod(DO_NOTHING)

    // Do not secure the window of view once media.
    val setFlags = Window::class.java.getMethod("setFlags", Int::class.java, Int::class.java)
    ::secureWindowFlagsMethod.hookMethod(scopedHook(setFlags) {
        before {
            it.args[0] = (it.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
    })
}
