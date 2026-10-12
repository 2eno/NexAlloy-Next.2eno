package io.github.nexalloy.piko.instagram

import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.opcodeEnum
import io.github.nexalloy.morphe.opcodes
import io.github.nexalloy.morphe.resourceMappings
import io.github.nexalloy.piko.inDexOrder
import io.github.nexalloy.piko.isStatic
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
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

/**
 * Instagram 447 no longer uses the "suggested_businesses" and "in_feed_survey" keys in the feed item parser, so like
 * Piko only the remaining keys are matched.
 */
val feedItemParseFromJsonMethod = findMethodDirect {
    findMethodWithStrings(suggestedContentKeys - "in_feed_survey") {
        name = "unsafeParseFromJson"
        returnType = "java.lang.Object"
        paramCount = 1
    }.single()
}

/**
 * The method the feed item parser reads each JSON key with: its result is switched on by hash code, like in Piko's
 * patch.
 */
val feedJsonKeyReaderMethod = findMethodDirect {
    val instructions = feedItemParseFromJsonMethod().instructions
    val hashIndex = instructions.indexOfFirst {
        it.methodRef?.let { method -> method.className == "java.lang.String" && method.name == "hashCode" } == true
    }
    val keyIndex = (0 until hashIndex).last { instructions[it].opcodeEnum == Opcode.MOVE_RESULT_OBJECT }
    instructions[keyIndex - 1].methodRef!!.also { require(it.returnTypeName == "java.lang.String") }
}

/**
 * Like Piko, the checker is the method of the class logging "is_benefit_active" that looks the benefit up in the set
 * of active benefits.
 */
val activeBenefitCheckerMethod = findMethodDirect {
    fun MethodData.invokes(className: String, name: String) =
        instructions.any { it.methodRef?.let { method -> method.className == className && method.name == name } == true }
    methodsUsingStrings("is_benefit_active").mapNotNull { it.declaredClass }.distinctBy { it.name }
        .flatMap { it.methods }
        .single {
            Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                it.returnTypeName == "boolean" && it.paramTypeNames == listOf("java.lang.String") &&
                it.invokes("java.util.Set", "contains") &&
                it.invokes("java.util.concurrent.atomic.AtomicReference", "get")
        }
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

/**
 * The text watcher of the DM composer. Piko writes the literals in decimal, they are 0x800033 and 0x800013.
 */
val directComposerOnTextChangedMethod = findMethodDirect {
    findMethod {
        matcher {
            name = "afterTextChanged"
            usingNumbers(0x800033, 0x800013)
        }
    }.firstNotNullOf { afterTextChanged ->
        afterTextChanged.declaredClass!!.findMethod { matcher { name = "onTextChanged" } }.singleOrNull()
    }
}

/**
 * Tells the other side that you are typing, the first `(boolean)void` call of the composer's text watcher.
 */
val directTypingStatusMethod = findMethodDirect {
    directComposerOnTextChangedMethod().instructions.firstNotNullOf { instruction ->
        instruction.methodRef?.takeIf { it.returnTypeName == "void" && it.paramTypeNames == listOf("boolean") }
    }
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
    }.let { methods -> methods.singleOrNull { it.name == "onInterceptTouchEvent" } ?: methods.inDexOrder().first() }
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

// region Download

internal const val MEDIA_CLASS = "com.instagram.feed.media.Media"
internal const val MEDIA_OPTION_CLASS = "com.instagram.feed.media.mediaoption.MediaOption\$Option"

/**
 * Builds the options of the post overflow menu, like Piko's fingerprint it is the lambda using "TEXT_POST_APP_INACTIVE".
 */
val overflowMenuOptionsMethod = findMethodDirect {
    methodsUsingStrings("TEXT_POST_APP_INACTIVE").single()
}

/**
 * Creates the options of the post overflow menu, its module name is "MediaOptionsOverflowMenuCreator".
 */
val overflowMenuCreatorClass = findClassDirect {
    methodsUsingStrings("MediaOptionsOverflowMenuCreator").single().declaredClass!!
}

/**
 * Handles the option tapped in the post overflow menu.
 */
val overflowMenuOptionClickMethod = findMethodDirect {
    findMethodWithStrings(listOf("MediaOptionsOverflowHelper")) {
        returnType = "void"
        paramTypes(MEDIA_OPTION_CLASS)
    }.single()
}

/**
 * The getter of a media value by its JSON key. Instagram 447 keeps many posts in a native live tree instead of the
 * fields of `Media`, and these getters read either.
 */
private fun DexKitBridge.mediaGetter(key: String, returnType: String) =
    findMethodWithStrings(listOf(key)) {
        declaredClass(MEDIA_CLASS)
        paramCount = 0
        this.returnType = returnType
    }.single()

val mediaImageInfoGetter = findMethodDirect {
    mediaGetter("image_versions2", "com.instagram.model.mediasize.ImageInfo")
}

val mediaVideoVersionsGetter = findMethodDirect {
    mediaGetter("video_versions", "java.util.List")
}

val mediaCarouselGetter = findMethodDirect {
    mediaGetter("carousel_media", "java.util.List")
}

/**
 * Module names of the bottom sheets showing the menu of a post: the reels menu, also used for posts shown like
 * reels, and the simplified menu of the "≡" button of posts in the home feed.
 */
internal val SHEET_MENU_MODULES = listOf(
    "ClipsOrganicMediaItemViewMoreOptionsController",
    "SimplifiedMediaOverflowBottomSheet",
)

/**
 * The bottom sheet builder created right after a menu module name, like in Piko's reels menu hook.
 */
private fun MethodData.sheetMenuBuilderClass(): ClassData? {
    val instructions = instructions
    val moduleIndex = instructions.indexOfFirst { it.string in SHEET_MENU_MODULES }
    if (moduleIndex < 0) return null
    return instructions.drop(moduleIndex + 1).firstOrNull { it.opcodeEnum == Opcode.NEW_INSTANCE }?.classRef
}

/**
 * Show the bottom sheet menus of posts and reels. The home feed filters the options of its simplified menu to a few
 * allowed ones, so the download item is added to the sheet itself.
 */
val sheetMenuMethods = findMethodListDirect {
    SHEET_MENU_MODULES.flatMap { module -> findMethodWithStrings(listOf(module)) }
        .filter { it.sheetMenuBuilderClass() != null }
        .distinctBy { it.descriptor }
}

val sheetMenuBuilderClass = findClassDirect {
    sheetMenuMethods().mapNotNull { it.sheetMenuBuilderClass() }.distinctBy { it.name }.single()
}

/**
 * The labels of the menu of other people's stories, built by the "ReelOptionsOverflowHelper". Like Piko, the
 * download label is added there.
 */
val storyMenuLabelsMethod = findMethodDirect {
    findMethodWithStrings(listOf("[INTERNAL] Pause Playback")) {
        returnType = "java.lang.CharSequence[]"
        paramCount = 1
    }.single()
}

/**
 * Shows the story menu sheet with its rows, the method creating the sheet whose module is
 * "stories_mimicry_menu_bottom_sheet".
 */
val storyMenuSheetMethod = findMethodDirect {
    val sheetClass = methodsUsingStrings("stories_mimicry_menu_bottom_sheet").single().declaredClassName
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(
                "java.util.List", "kotlin.jvm.functions.Function0", "kotlin.jvm.functions.Function0",
                "kotlin.jvm.functions.Function0", "kotlin.jvm.functions.Function1", null,
            )
        }
    }.single { method -> method.instructions.any { it.classRef?.name == sheetClass } }
}

/**
 * A row of the story menu sheet, created with
 * `(?, ImageUrl, ?, Integer, String title, String subtitle, String, Function0 onClick, int icon, boolean, boolean)`.
 */
val storyMenuRowConstructor = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            paramTypes(
                null, "com.instagram.common.typedurl.ImageUrl", null, "java.lang.Integer", "java.lang.String",
                "java.lang.String", "java.lang.String", "kotlin.jvm.functions.Function0", "int", "boolean", "boolean",
            )
        }
    }.single()
}

/**
 * The carousel position of a post, the int field of the post state read by the getter of `EditMediaInfoFragment`
 * returning the current media, like Piko's decoder:
 * `iget-object state; if-eqz; iget position, state; ...; invoke-static (List, int)Media`.
 */
val mediaPositionField = findFieldDirect {
    fun MethodData.positionField(): FieldData? {
        val instructions = instructions.take(10)
        if (instructions.size < 10 || instructions[1].opcodeEnum != Opcode.IF_EQZ) return null
        val state = instructions[0].takeIf { it.opcodeEnum == Opcode.IGET_OBJECT }?.fieldRef ?: return null
        val position = instructions[2].takeIf { it.opcodeEnum == Opcode.IGET }?.fieldRef ?: return null
        val media = instructions[9].takeIf { it.opcodeEnum == Opcode.INVOKE_STATIC }?.methodRef ?: return null
        if (position.declaredClassName != state.typeName || position.typeName != "int") return null
        if (media.paramTypeNames != listOf("java.util.List", "int") || media.returnTypeName != MEDIA_CLASS) return null
        return position
    }
    findMethod {
        matcher {
            declaredClass("instagram.features.creation.fragment.EditMediaInfoFragment")
            returnType = "java.lang.String"
        }
    }.mapNotNull { it.positionField() }.distinctBy { it.descriptor }.single()
}

private const val VIDEO_VERSION_CLASS = "com.instagram.api.schemas.VideoVersionIntf"

/**
 * The getter of a value of a video version, called right after its key in the method mapping a video version to
 * JSON, like Piko's video data decoder.
 */
private fun DexKitBridge.videoVersionGetter(key: String): MethodData {
    val mapper = findMethodWithStrings(listOf("fallback", "height", "type", "url", "url_expiration_timestamp_us", "width")) {
        returnType = "java.util.Map"
        paramTypes(VIDEO_VERSION_CLASS)
    }.single { it.isStatic }
    val instructions = mapper.instructions
    val keyIndex = instructions.indexOfFirst { it.string == key }
    return instructions[keyIndex + 1].methodRef!!.also {
        require(it.declaredClassName == VIDEO_VERSION_CLASS && it.paramTypeNames.isEmpty())
    }
}

val videoVersionWidthGetter = findMethodDirect { videoVersionGetter("width") }

val videoVersionHeightGetter = findMethodDirect { videoVersionGetter("height") }

/**
 * Builds the dialog of Instagram's dialog builder, the native dialog Piko shows its download options in.
 */
val igDialogBuildMethod = findMethodDirect {
    findMethodWithStrings(listOf("DialogBuilder - Activity is finishing")) {
        returnType = "android.app.Dialog"
    }.single()
}

/**
 * Shows a dialog of React Native with the dialog builder, the calls of its options name the builder methods.
 */
private fun DexKitBridge.igDialogHelperMethod() = findMethod {
    matcher {
        declaredClass("com.instagram.react.modules.base.IgReactDialogModule")
        name = "showDialogHelper"
    }
}.single()

/**
 * The builder method called after the option [key] is read.
 */
private fun DexKitBridge.igDialogOptionMethod(key: String): MethodData {
    val builder = igDialogBuildMethod().declaredClassName
    val instructions = igDialogHelperMethod().instructions
    val keyIndex = instructions.indexOfFirst { it.string == key }
    require(keyIndex >= 0) { "$key not found" }
    return instructions.drop(keyIndex + 1).first {
        it.opcodeEnum == Opcode.INVOKE_VIRTUAL && it.methodRef?.declaredClassName == builder
    }.methodRef!!
}

val igDialogCancelableMethod = findMethodDirect { igDialogOptionMethod("IS_CANCELABLE") }

val igDialogCanceledOnTouchOutsideMethod = findMethodDirect { igDialogOptionMethod("CANCELED_ON_TOUCH_OUTSIDE") }

val igDialogNegativeButtonMethod = findMethodDirect { igDialogOptionMethod("NEGATIVE_BUTTON_TEXT") }

/**
 * The title of the dialog builder, the first builder field set by the React Native dialog.
 */
val igDialogTitleField = findFieldDirect {
    val builder = igDialogBuildMethod().declaredClassName
    igDialogHelperMethod().instructions.first {
        it.opcodeEnum == Opcode.IPUT_OBJECT && it.fieldRef?.declaredClassName == builder
    }.fieldRef!!
}

/**
 * The flag rounding the bottom corners of the menu of a dialog. The builder rounds the last menu row even when
 * buttons follow it, so like Piko it is cleared. It is set right after the flag of the top corners:
 * `const/4 v, 1; iput-boolean v, adapter, top; iput-boolean v, adapter, bottom`.
 */
val igDialogMenuBottomCornersField = findFieldDirect {
    val buildMethod = igDialogBuildMethod()
    buildMethod.instructions.windowed(3).single { (constant, top, bottom) ->
        val topField = top.fieldRef
        val bottomField = bottom.fieldRef
        constant.opcodeEnum == Opcode.CONST_4 && constant.literal == 1L &&
            top.opcodeEnum == Opcode.IPUT_BOOLEAN && bottom.opcodeEnum == Opcode.IPUT_BOOLEAN &&
            topField != null && bottomField != null &&
            top.registerCount == 2 && bottom.registerCount == 2 &&
            top.register(0) == constant.register(0) && bottom.register(0) == top.register(0) &&
            bottom.register(1) == top.register(1) &&
            topField.declaredClassName == bottomField.declaredClassName &&
            topField.declaredClassName != buildMethod.declaredClassName && topField.name != bottomField.name
    }[2].fieldRef!!
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

/**
 * The headers of the main feed request, the first map of a request class the method sending the feed request uses.
 */
val mainFeedRequestHeadersField = findFieldDirect {
    val requestClasses = findClass {
        matcher { usingStrings(listOf("Request{mReason=", ", mInstanceNumber="), StringMatchType.Contains) }
    }.map { it.name }.toSet()
    methodsUsingStrings("pagination_source", "FEED_REQUEST_SENT").firstNotNullOf { method ->
        method.instructions.firstNotNullOfOrNull { instruction ->
            instruction.fieldRef?.takeIf { it.typeName == "java.util.Map" && it.declaredClassName in requestClasses }
        }
    }
}

val mainFeedRequestClass = findClassDirect {
    getClassData(mainFeedRequestHeadersField().declaredClassName)!!
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
