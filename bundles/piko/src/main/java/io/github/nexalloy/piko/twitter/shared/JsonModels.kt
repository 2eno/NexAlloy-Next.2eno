package io.github.nexalloy.piko.twitter.shared

import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.PatcherLog
import io.github.nexalloy.hookMethod
import io.github.nexalloy.piko.twitter.JSON_MODEL_PACKAGE
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Hooks the `parse` method of the LoganSquare mapper of the JSON model [modelClass], like Piko's hooks before the
 * mapper returns the parsed model. [onParsed] gets the model and returns the model to use instead.
 */
internal fun PatchExecutor.hookJsonModel(modelClass: String, onParsed: (model: Any) -> Any?) {
    val mapper = classLoader.loadClass("$modelClass\$\$JsonObjectMapper")
    val parse = mapper.declaredMethods.singleOrNull {
        it.name == "parse" && it.parameterTypes.size == 1 && it.returnType == Any::class.java
    } ?: throw NoSuchMethodException("${mapper.name}.parse")
    parse.hookMethod {
        after { param ->
            val model = param.result ?: return@after
            // Exceptions thrown by hooks reach X, so a failing callback keeps the parsed model.
            runCatching { onParsed(model) }
                .onSuccess { param.result = it }
                .onFailure { PatcherLog.error(it) { "Could not change ${model.javaClass.name}" } }
        }
    }
}

internal fun jsonModel(name: String) = "$JSON_MODEL_PACKAGE.$name"

private val instanceFields = ConcurrentHashMap<Class<*>, List<Field>>()

/**
 * The instance fields declared by [clazz], accessible.
 */
internal fun instanceFields(clazz: Class<*>): List<Field> = instanceFields.getOrPut(clazz) {
    clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
}
