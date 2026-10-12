package io.github.nexalloy.`2eno`.instagram

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

// Fingerprints for the stealth follow patch, checked against Instagram 447.0.0.55.81.

internal const val USER_SESSION_CLASS = "com.instagram.common.session.UserSession"
internal const val REEL_RESPONSE_ITEM = "com.instagram.model.reels.ReelResponseItem"

/**
 * Adds the reels of a story tray response to the reel store, the reels the story tray shows. It logs the systrace
 * section "ReelStore.maybeAddNewTrayReelResponseItemIntfs".
 */
val reelStoreAddTrayReelsMethod = findMethodDirect {
    findMethod {
        matcher { usingStrings(listOf("ReelStore.maybeAddNewTrayReelResponseItemIntfs"), StringMatchType.Equals) }
    }.single()
}

/**
 * Parses a reel (`ReelResponseItem`) from JSON. The parser class also writes a reel to JSON with a
 * `(JsonGenerator, ReelResponseItem)` method, so it is found by that method.
 */
val reelResponseParserMethod = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(null, REEL_RESPONSE_ITEM)
        }
    }.mapNotNull { it.declaredClass }.distinctBy { it.name }
        .flatMap { it.methods }
        .single { it.name == "unsafeParseFromJson" }
}

/**
 * Static methods creating the JSON parser `unsafeParseFromJson` reads, from a string. There may be a few, the patch
 * uses whichever one works.
 */
val jsonParserFromStringMethods = findMethodListDirect {
    val parserClass = findMethod {
        matcher {
            returnType = "void"
            paramTypes(null, REEL_RESPONSE_ITEM)
        }
    }.mapNotNull { it.declaredClass }.distinctBy { it.name }
        .flatMap { it.methods }
        .single { it.name == "unsafeParseFromJson" }
        .paramTypeNames.single()
    findMethod {
        matcher {
            returnType = parserClass
            paramTypes("java.lang.String")
        }
    }.filter { Modifier.isStatic(it.modifiers) }
}

/**
 * The request dispatcher of Instagram, so the patch can read the headers of the app's own API requests.
 */
val tigonStartRequestMethods = findMethodListDirect {
    findMethod {
        matcher {
            declaredClass("com.instagram.api.tigon.TigonServiceLayer")
            name = "startRequest"
        }
    }
}

/**
 * Adds the buttons below the expanded profile picture (Follow, Share profile, Copy link, QR code):
 * `(ViewGroup bar, UserSession, List actions, boolean, boolean)`, inflating a view for each action between spaces.
 */
val profilePictureActionsMethod = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("android.view.ViewGroup", USER_SESSION_CLASS, "java.util.List", "boolean", "boolean")
            addInvoke {
                declaredClass("android.view.LayoutInflater")
                name = "inflate"
            }
            addInvoke {
                declaredClass("android.widget.Space")
                name = "<init>"
            }
        }
    }.single()
}

/**
 * A button below the expanded profile picture, created with `(String label, Function0 onClick, int icon, boolean)`.
 */
val profilePictureActionConstructor = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("android.view.ViewGroup", USER_SESSION_CLASS, "java.util.List", "boolean", "boolean")
            addInvoke {
                declaredClass("android.view.LayoutInflater")
                name = "inflate"
            }
            addInvoke {
                declaredClass("android.widget.Space")
                name = "<init>"
            }
        }
    }.single().instructions.mapNotNull { it.classRef }.distinctBy { it.name }
        .flatMap { it.methods }
        .single {
            it.isConstructor && it.paramTypeNames ==
                listOf("java.lang.String", "kotlin.jvm.functions.Function0", "int", "boolean")
        }
}
