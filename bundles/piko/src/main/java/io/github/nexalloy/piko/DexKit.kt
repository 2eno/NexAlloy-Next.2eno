package io.github.nexalloy.piko

import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal val MethodData.isStatic get() = Modifier.isStatic(modifiers)

/**
 * Methods of a class in the order of the dex file: direct methods first, then virtual methods,
 * each sorted by name and prototype.
 */
internal fun List<MethodData>.inDexOrder(): List<MethodData> {
    fun MethodData.returnDescriptor() = descriptor.substringAfterLast(')')
    fun MethodData.parameterDescriptor() = descriptor.substringAfter('(').substringBefore(')')
    val (direct, virtual) = partition { it.isConstructor || it.isStaticInitializer || it.isStatic || Modifier.isPrivate(it.modifiers) }
    val order = compareBy<MethodData>({ it.name }, { it.returnDescriptor() }, { it.parameterDescriptor() })
    return direct.sortedWith(order) + virtual.sortedWith(order)
}
