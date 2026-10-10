package io.github.nexalloy.piko.instagram

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.opcodeEnum
import io.github.nexalloy.morphe.opcodes
import io.github.nexalloy.morphe.resourceMappings
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

// Ported from the Piko Instagram patches (https://github.com/crimera/piko).

internal const val USER_SESSION_CLASS = "com.instagram.common.session.UserSession"

/**
 * Methods using all [strings], preferring exact matches over partial ones.
 */
private fun DexKitBridge.findMethodWithStrings(
    strings: List<String>,
    block: MethodMatcher.() -> Unit = {},
): List<MethodData> {
    fun query(matchType: StringMatchType) = findMethod {
        matcher {
            block()
            usingStrings(strings, matchType)
        }
    }
    return query(StringMatchType.Equals).ifEmpty { query(StringMatchType.Contains) }
}

private fun DexKitBridge.methodsUsingStrings(vararg strings: String) = findMethodWithStrings(strings.toList())

private fun List<MethodData>.parseFromJson() = filter { it.name.lowercase().contains("parsefromjson") }

private val MethodData.isStatic get() = Modifier.isStatic(modifiers)

/**
 * The first String field stored after the usage of [string].
 */
private fun MethodData.stringFieldStoredAfter(string: String): FieldData {
    val instructions = instructions
    val stringIndex = instructions.indexOfFirst { it.string?.contains(string) == true }
    if (stringIndex < 0) throw Exception("String $string not found in $this")
    return instructions.drop(stringIndex + 1).first {
        it.opcodeEnum == Opcode.IPUT_OBJECT && it.fieldRef?.typeName == "java.lang.String"
    }.fieldRef!!
}

/**
 * Methods of a class in the order of the dex file: direct methods first, then virtual methods,
 * each sorted by name and prototype.
 */
private fun List<MethodData>.inDexOrder(): List<MethodData> {
    fun MethodData.returnDescriptor() = descriptor.substringAfterLast(')')
    fun MethodData.parameterDescriptor() = descriptor.substringAfter('(').substringBefore(')')
    val (direct, virtual) = partition { it.isConstructor || it.isStaticInitializer || it.isStatic || Modifier.isPrivate(it.modifiers) }
    val order = compareBy<MethodData>({ it.name }, { it.returnDescriptor() }, { it.parameterDescriptor() })
    return direct.sortedWith(order) + virtual.sortedWith(order)
}

// region Ads

val adInjectorMethod = findMethodDirect {
    methodsUsingStrings("Is ad pod").single()
}

internal val suggestedContentKeys = listOf(
    "clips_netego",
    "stories_netego",
    "in_feed_survey",
    "bloks_netego",
    "suggested_igd_channels",
    "suggested_top_accounts",
    "suggested_users",
)

val feedItemParseFromJsonMethod = findMethodDirect {
    findMethodWithStrings(listOf("suggested_businesses") + suggestedContentKeys).parseFromJson().single()
}

/**
 * The feed item fields holding suggested content.
 * The parser stores the value of a key right after comparing it.
 */
val suggestedContentFields = findFieldListDirect {
    val instructions = feedItemParseFromJsonMethod().instructions
    suggestedContentKeys.mapNotNull { key ->
        val keyIndex = instructions.indexOfFirst { it.string == key }
        if (keyIndex < 0) return@mapNotNull null
        instructions.drop(keyIndex + 1).take(12)
            .takeWhile { it.string == null }
            .firstOrNull { it.opcodeEnum == Opcode.IPUT_OBJECT }?.fieldRef
    }.distinctBy { it.descriptor }
}

val activeBenefitCheckerMethod = findMethodDirect {
    findMethodWithStrings(listOf("is_benefit_active")) {
        returnType = "boolean"
        paramTypes("java.lang.String")
    }.single()
}

// endregion

// region Privacy

val storySeenStateClass = findClassDirect {
    methodsUsingStrings("media/seen/?reel=%s&live_vod=0").first().declaredClass!!
}

/**
 * The last boolean method of the class marking stories as seen returns whether the story is seen.
 */
val storySeenStateMethod = findMethodDirect {
    storySeenStateClass().methods.inDexOrder().last { it.returnTypeName == "boolean" }
}

val markThreadSeenMethod = findMethodDirect {
    findMethodWithStrings(listOf("mark_thread_seen-")) {
        returnType = "void"
    }.filter { it.isStatic && Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) }.single()
}

val directComposerOnTextChangedMethod = findMethodDirect {
    findMethod {
        matcher {
            name = "afterTextChanged"
            usingNumbers(0x800032, 0x800013)
        }
    }.single().declaredClass!!.findMethod {
        matcher { name = "onTextChanged" }
    }.single()
}

val screenshotDetectorMethod = findMethodDirect {
    methodsUsingStrings("ig_android_story_screenshot_directory", "screenshot_detector").single()
}

/**
 * The detector starts observing the screenshot directory right before it uses its name.
 */
val screenshotObserverStartMethod = findMethodDirect {
    val instructions = screenshotDetectorMethod().instructions
    val stringIndex = instructions.indexOfFirst { it.string == "ig_android_story_screenshot_directory" }
    instructions.take(stringIndex).last { it.opcodeEnum == Opcode.INVOKE_VIRTUAL }.methodRef!!
}

val directScreenshotCaptureMethod = findMethodDirect {
    methodsUsingStrings("igd_screenshot_capture").single { it.returnTypeName == "void" }
}

val secureWindowFlagsMethod = findMethodDirect {
    findMethodWithStrings(listOf("Inconsistency in window FLAG_SECURE state detected! window state: ")).single()
}

// endregion

// region Links

val tigonStartRequestMethods = findMethodListDirect {
    findMethod {
        matcher {
            declaredClass("com.instagram.api.tigon.TigonServiceLayer")
            name = "startRequest"
        }
    }
}

val permalinkResponseParserMethod = findMethodDirect {
    methodsUsingStrings("XDTPermalinkResponse").parseFromJson().single()
}

val permalinkResponseUrlField = findFieldDirect {
    permalinkResponseParserMethod().stringFieldStoredAfter("XDTPermalinkResponse")
}

val profileShareUrlParserMethod = findMethodDirect {
    methodsUsingStrings("profile_to_share_url").parseFromJson().single()
}

val profileShareUrlField = findFieldDirect {
    profileShareUrlParserMethod().stringFieldStoredAfter("profile_to_share_url")
}

val audioShareUrlParserMethod = findMethodDirect {
    methodsUsingStrings("audio_to_share_url").parseFromJson().single()
}

val audioShareUrlField = findFieldDirect {
    audioShareUrlParserMethod().stringFieldStoredAfter("audio_to_share_url")
}

val thirdPartySharingUrlGetters = findMethodListDirect {
    val classes = findClass {
        matcher { className("StoryItemThirdPartySharingUrlResponseImpl", StringMatchType.EndsWith) }
    } + findClass {
        matcher { className("com.instagram.api.schemas.LiveThirdPartySharingUrlResponseImpl") }
    }
    classes.flatMap { it.methods }.filter {
        !it.isStatic && it.paramCount == 0 && it.returnTypeName == "java.lang.String"
    }
}

val inAppBrowserOpenMethod = findMethodDirect {
    findMethodWithStrings(listOf("Tracking.ARG_CLICK_SOURCE", "TrackingInfo.ARG_MODULE_NAME")) {
        returnType = "boolean"
    }.single()
}

// endregion

// region Layout

private const val NOTES_TAG = "enable_media_notes_production"
internal val notesTagHash = NOTES_TAG.hashCode()

val mediaNotesEnabledMethod = findMethodDirect {
    findMethodWithStrings(listOf(NOTES_TAG)) {
        returnType = "java.lang.Boolean"
        usingNumbers(notesTagHash)
    }.single()
}

val navigationButtonsListMethod = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.util.List"
            paramTypes(USER_SESSION_CLASS, "boolean")
            opcodes(Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT)
        }
    }.single { !it.isStatic && Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) }
}

val notesTrayRecyclerViewField = findFieldDirect {
    val recyclerViewId = resourceMappings["id", "cf_hub_recycler_view"].toLong()
    val method = findMethod { matcher { usingNumbers(recyclerViewId) } }.single()
    val instructions = method.instructions
    val idIndex = instructions.indexOfFirst { it.literal == recyclerViewId }
    instructions.drop(idIndex + 1).first { it.opcodeEnum == Opcode.IPUT_OBJECT }.fieldRef!!
}

val notesTrayBuilderMethod = findMethodDirect {
    val recyclerViewId = resourceMappings["id", "cf_hub_recycler_view"].toLong()
    findMethod { matcher { usingNumbers(recyclerViewId) } }.single()
}

val postOnDoubleTapMethod = findMethodDirect {
    findMethodWithStrings(listOf("open_cmon_interstitial")) {
        name = "onSingleTapConfirmed"
    }.single().declaredClass!!.findMethod { matcher { name = "onDoubleTap" } }.single()
}

val reelOnDoubleTapMethod = findMethodDirect {
    findMethod {
        matcher {
            name = "onFling"
            paramTypes("android.view.MotionEvent", "android.view.MotionEvent", "float", "float")
            opcodes(Opcode.IF_EQZ, Opcode.IF_EQZ)
        }
    }.firstNotNullOf { onFling ->
        onFling.declaredClass!!.findMethod { matcher { name = "onDoubleTap" } }.singleOrNull()
    }
}

val commentOnDoubleTapMethods = findMethodListDirect {
    findMethodWithStrings(listOf("comment_row_component", "fb_comment_double_tap")) {
        name = "onDoubleTap"
    }
}

val messageOnDoubleTapMethod = findMethodDirect {
    val listener = "android.view.GestureDetector\$SimpleOnGestureListener"
    findClass {
        matcher {
            superClass(listener)
            addMethod {
                name = "<init>"
                paramTypes(listener, "android.view.View", "android.widget.TextView", "boolean")
            }
        }
    }.single().findMethod { matcher { name = "onDoubleTap" } }.single()
}

val clipsViewPagerGetViewAtIndexMethod = findMethodDirect {
    methodsUsingStrings("ClipsViewPagerImpl_getViewAtIndex").single()
}

val clipsSwipeRefreshInterceptTouchMethod = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout")
            paramTypes("android.view.MotionEvent")
            returnType = "boolean"
        }
    }.single()
}

/**
 * Called when the progress of a story completed.
 */
val storyProgressCompletedMethod = findMethodDirect {
    findMethodWithStrings(listOf("userSession")) {
        declaredClass("instagram.features.stories.fragment.ReelViewerFragment")
        returnType = "void"
        paramTypes("java.lang.Object")
    }.first()
}

// endregion

// region Misc

val videoAutoplayDisabledMethod = findMethodDirect {
    findMethodWithStrings(listOf("ig_olympus_disable_video_autoplay", "ig_disable_video_autoplay", "ig_video_setting")) {
        returnType = "boolean"
    }.single()
}

val storiesAudioEnabledMethod = findMethodDirect {
    findMethodWithStrings(listOf("audio state did not match")) {
        returnType = "void"
        paramTypes("boolean", "int")
    }.single().declaredClass!!.methods.inDexOrder().filter {
        it.paramCount == 0 && it.returnTypeName == "boolean"
    }[2]
}

val bloksFullScreenOpenMethod = findMethodDirect {
    findMethodWithStrings(listOf("BKDataFetcher.fetch")) {
        returnType = "void"
        paramTypes("android.content.Context", "com.instagram.bloks.hosting.IgBloksScreenConfig")
    }.single { !it.isStatic }
}

val bloksFullScreenAppIdField = findFieldDirect {
    val method = bloksFullScreenOpenMethod()
    method.instructions.firstNotNullOf { instruction ->
        instruction.fieldRef?.takeIf {
            instruction.opcodeEnum == Opcode.IGET_OBJECT &&
                it.declaredClassName == method.declaredClassName && it.typeName == "java.lang.String"
        }
    }
}

/**
 * The first method of the class checking the "snooze_expiration_lockout_manager" flag stores the app age.
 */
val buildAgeInitMethod = findMethodDirect {
    findMethodWithStrings(listOf("snooze_expiration_lockout_manager")) {
        returnType = "boolean"
    }.first().declaredClass!!.methods.inDexOrder().first()
}

val buildAgeField = findFieldDirect {
    buildAgeInitMethod().instructions.last { it.opcodeEnum == Opcode.IPUT }.fieldRef!!
}

val mainFeedRequestClass = findClassDirect {
    findClass {
        matcher { usingStrings(listOf("Request{mReason=", ", mInstanceNumber="), StringMatchType.Contains) }
    }.single()
}

val mainFeedRequestHeadersField = findFieldDirect {
    val requestClass = mainFeedRequestClass().name
    methodsUsingStrings("pagination_source", "FEED_REQUEST_SENT").single().usingFields.first {
        it.field.declaredClassName == requestClass && it.field.typeName == "java.util.Map"
    }.field
}

val userAgentDisplayMetricsMethod = findMethodDirect {
    findMethodWithStrings(listOf("%sdpi; %sx%s")) {
        returnType = "java.lang.String"
        paramTypes("android.content.Context")
    }.single()
}

val homeIconOnLongClickMethod = findMethodDirect {
    findMethodWithStrings(listOf("click", "activity")) {
        name = "onLongClick"
    }.single()
}

/**
 * The listener starts by loading the activity and the user session.
 */
private fun DexKitBridge.homeIconListenerField(index: Int) =
    homeIconOnLongClickMethod().instructions.take(2)[index].takeIf { it.opcodeEnum == Opcode.IGET_OBJECT }!!.fieldRef!!

val homeIconActivityField = findFieldDirect { homeIconListenerField(0) }

val homeIconUserSessionField = findFieldDirect { homeIconListenerField(1) }

val developerOptionsClass = findClassDirect {
    methodsUsingStrings("debug_options_error").first { it.isStatic && Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) }.declaredClass!!
}

// endregion
