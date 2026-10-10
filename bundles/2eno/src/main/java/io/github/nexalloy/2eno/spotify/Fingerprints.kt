package io.github.nexalloy.`2eno`.spotify

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

// region Ads

val contextMenuViewModelClass = findClassDirect {
    runCatching {
        fingerprint { strings("ContextMenuViewModel(header=") }
    }.getOrElse {
        fingerprint {
            accessFlags(AccessFlags.CONSTRUCTOR)
            strings("ContextMenuViewModel cannot contain items with duplicate itemResId. id=")
            parameters("L", "Ljava/util/List;", "Z")
        }
    }.declaredClass!!
}

/**
 * The context menu item view model is `ViewModel(available, enabled, isPremiumUpsell, ...)`.
 */
val premiumUpsellField = findFieldDirect {
    val viewModelClass = findMethod {
        findFirst = true
        matcher { name = "getViewModel" }
    }.single().returnType!!
    viewModelClass.fields.filter { it.typeName == "boolean" }[1]
}

private fun DexKitBridge.sectionsGetters(structureClass: String) = findClass {
    matcher { className(structureClass, StringMatchType.EndsWith) }
}.findMethod {
    matcher {
        returnType = "java.util.List"
        addUsingField { name("sections_") }
    }
}

val casitaHomeSectionsGetters = findMethodListDirect { sectionsGetters("casita.v1.resolved.HomeStructure") }

val homeSectionsGetters = findMethodListDirect { sectionsGetters("homeapi.proto.HomeStructure") }

val browseSectionsGetters = findMethodListDirect { sectionsGetters("browsita.v1.resolved.BrowseStructure") }

private fun DexKitBridge.pendragonRequests(requestClass: String) = findMethod {
    matcher {
        name = "apply"
        addInvoke {
            name = "<init>"
            declaredClass(requestClass, StringMatchType.EndsWith)
        }
    }
}

val pendragonRequestMethods = findMethodListDirect {
    pendragonRequests(".FetchMessageRequest") + pendragonRequests(".FetchMessageListRequest")
}

val embeddedAdPlaylistPropertiesConstructor = findMethodDirect {
    findClass {
        matcher { usingStrings("android-embeddedadplaylist", "embedded_ad_placement") }
    }.single().findMethod {
        matcher { name = "<init>" }
    }.single { it.paramCount > 2 }
}

/**
 * Plugin entries of the now playing view are created with `(String name, Plugin plugin, boolean enabled)`.
 */
val npvPluginEntryConstructors = findMethodListDirect {
    findMethod {
        matcher { usingStrings("EmbeddedAdNpvPlugin") }
    }.flatMap { it.invokes }.filter { method ->
        val parameters = method.paramTypeNames
        method.isConstructor && parameters.size == 3 &&
            parameters[0] == "java.lang.String" && parameters[2] == "boolean"
    }.distinctBy { it.descriptor }
}

// endregion

// region Layout

val navigationTabEnumClass = findClassDirect {
    findClass {
        matcher {
            superClass("java.lang.Enum")
            usingStrings(listOf("HOME", "SEARCH", "YOUR_LIBRARY", "PREMIUM", "CREATE"), StringMatchType.Equals)
        }
    }.single()
}

/**
 * The bottom navigation is created with `(Tab, Tab, Tab, Tab, Tab, int flags)`.
 */
val navigationTabSetConstructor = findMethodDirect {
    val tabEnum = navigationTabEnumClass().name
    val tabClasses = findClass {
        matcher { addField { type(tabEnum) } }
    }.map { it.name }.toSet()

    findMethod {
        matcher {
            name = "<init>"
            paramCount = 6
        }
    }.single { method ->
        val parameters = method.paramTypeNames
        parameters[5] == "int" && parameters.take(5).distinct().singleOrNull() in tabClasses
    }
}

// endregion

// region Misc

val canBindAppWidgetPermissionMethod = findMethodDirect {
    runCatching {
        fingerprint {
            returns("Z")
            strings("android.permission.BIND_APPWIDGET")
            opcodes(Opcode.AND_INT_LIT8)
        }
    }.getOrElse {
        // Newer versions no longer check the system app flag in the same method.
        fingerprint {
            returns("Z")
            strings("android.permission.BIND_APPWIDGET")
        }
    }
}

// endregion

// region Privacy

val shareCopyUrlMethod = findMethodDirect {
    runCatching {
        fingerprint {
            returns("Ljava/lang/Object;")
            parameters("Ljava/lang/Object;")
            strings("clipboard", "Spotify Link")
            methodMatcher { name = "invokeSuspend" }
        }
    }.getOrElse {
        fingerprint {
            returns("Ljava/lang/Object;")
            parameters("Ljava/lang/Object;")
            strings("clipboard", "createNewSession failed")
            methodMatcher { name = "apply" }
        }
    }
}

val formatAndroidShareSheetUrlMethod = findMethodDirect {
    runCatching {
        fingerprint {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
            returns("Ljava/lang/String;")
            parameters("L", "Ljava/lang/String;")
            methodMatcher {
                addCaller { usingStrings("android.intent.extra.TEXT", "text/plain") }
            }
        }
    }.recoverCatching {
        fingerprint {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
            returns("Ljava/lang/String;")
            parameters("L", "Ljava/lang/String;")
            literal { '\n'.code }
        }
    }.getOrElse {
        fingerprint {
            accessFlags(AccessFlags.PUBLIC)
            returns("Ljava/lang/String;")
            parameters("Lcom/spotify/share/social/sharedata/ShareData;", "Ljava/lang/String;")
        }
    }
}

// endregion
