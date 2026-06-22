package io.github.tt432.k9tools

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.GlobalSearchScope

/**
 * @author QiuShui1012
 */

/**
 * 从字段列表中获取最终的 ByteBuf 类型
 * 遍历所有字段，通过 findByteBufType 获取每个字段的 ByteBuf 类型
 * 然后找出继承链中最底层的 ByteBuf 子类
 * 如果有多个不同的子类，返回 io.netty.buffer.ByteBuf
 *
 * @param fields 字段数组
 * @param project 当前项目
 * @return 最终的 ByteBuf 类型的全限定类名
 */
fun getFinalByteBufType(fields: Array<out PsiField>, project: Project): String {
    if (fields.isEmpty()) {
        return "io.netty.buffer.ByteBuf"
    }

    // 收集所有 ByteBuf 类型
    val byteBufTypes = mutableListOf<PsiType>()
    for (field in fields) {
        val byteBufType = findByteBufType(field, project)
        if (byteBufType is PsiClassType) {
            val resolved = byteBufType.resolve()
            if (resolved != null && isByteBufType(byteBufType)) {
                byteBufTypes.add(byteBufType)
            }
        }
    }

    if (byteBufTypes.isEmpty()) {
        return "io.netty.buffer.ByteBuf"
    }

    // 如果只有一个类型，直接返回
    if (byteBufTypes.size == 1) {
        return byteBufTypes[0].canonicalText
    }

    // 构建继承层级
    val typeHierarchy = mutableMapOf<String, MutableSet<String>>()

    for (type in byteBufTypes) {
        if (type !is PsiClassType) {
            continue
        }

        val typeName = type.canonicalText
        val psiClass = type.resolve() ?: continue

        // 获取该类型的所有父类
        val superTypes = mutableSetOf<String>()
        var superClass = psiClass.superClass
        var depth = 0
        while (superClass != null && depth < 50) {
            val superName = superClass.qualifiedName
            if (superName != null) {
                superTypes.add(superName)
            }
            superClass = superClass.superClass
            depth++
        }

        // 也检查实现的接口
        val interfaces = psiClass.interfaces.map { it.qualifiedName }.filterNotNull()
        superTypes.addAll(interfaces)

        typeHierarchy[typeName] = superTypes
    }

    // 找到最底层的类型（没有子类在集合中的类型）
    val allTypes = typeHierarchy.keys.toSet()

    val deepestTypes = mutableSetOf<String>()

    for ((type, _) in typeHierarchy) {
        var hasChild = false
        for (otherType in allTypes) {
            if (otherType == type) continue
            val otherSuperTypes = typeHierarchy[otherType]
            if (otherSuperTypes != null && otherSuperTypes.contains(type)) {
                hasChild = true
                break
            }
        }
        if (!hasChild) {
            deepestTypes.add(type)
        }
    }

    // 如果有多个最底层类型（继承链冲突），返回 ByteBuf
    if (deepestTypes.size != 1) {
        return "io.netty.buffer.ByteBuf"
    }

    return deepestTypes.firstOrNull() ?: "io.netty.buffer.ByteBuf"
}

/**
 * 从字段中提取 StreamCodec 的 ByteBuf 泛型类型
 * 查找名为 STREAM_CODEC 的常量字段，提取其泛型参数 B（即 ByteBuf 类型）
 * 如果未找到，返回 ByteBuf 类型
 * 支持 List、Map、Optional 等容器类型 - 会递归检查容器内元素的 STREAM_CODEC
 *
 * @param field 要检查的字段
 * @param project 当前项目
 * @return 表示 ByteBuf 类型的 PsiType
 */
private fun findByteBufType(field: PsiField, project: Project): PsiType {
    val fieldType = field.type
    if (fieldType !is PsiClassType) {
        return getByteBufType(project)
    }

    val resolvedClass = fieldType.resolve() ?: return getByteBufType(project)

    // 0. 首先检查是否是容器类型（List、Map、Optional等），如果是则检查容器内元素的 STREAM_CODEC
    val containerElementByteBuf = extractByteBufFromContainerElement(fieldType, project)
    if (containerElementByteBuf != null) {
        return containerElementByteBuf
    }

    // 1. 检查当前类本身是否有 STREAM_CODEC 字段
    val codecField = resolvedClass.allFields.firstOrNull {
        it.name == "STREAM_CODEC" &&
        it.hasModifierProperty(PsiModifier.STATIC) &&
        it.hasModifierProperty(PsiModifier.FINAL)
    }

    if (codecField != null) {
        val codecType = extractByteBufFromType(codecField.type, project)
        if (codecType != null) return codecType
    }

    // 2. 检查类本身是否实现了 StreamCodec，直接提取泛型参数
    val streamCodecByteBuf = findStreamCodecByteBuf(resolvedClass, project)
    if (streamCodecByteBuf != null) {
        return streamCodecByteBuf
    }

    // 3. 都未找到，返回 ByteBuf
    return getByteBufType(project)
}

/**
 * 从容器类型中提取元素类型的 ByteBuf
 * 支持：
 * - List<SomeClass> -> 检查 SomeClass 的 STREAM_CODEC
 * - Map<String, SomeClass> -> 检查 SomeClass（值类型）的 STREAM_CODEC
 * - Map<SomeClass, String> -> 检查 SomeClass（键类型）的 STREAM_CODEC
 * - Optional<SomeClass> -> 检查 SomeClass 的 STREAM_CODEC
 * - 嵌套容器：List<Map<String, SomeClass>> -> 递归检查 SomeClass
 *
 * @param type 要检查的容器类型
 * @param project 当前项目
 * @return 找到的 ByteBuf 类型，如果没有则返回 null
 */
private fun extractByteBufFromContainerElement(type: PsiType, project: Project): PsiType? {
    if (type !is PsiClassType) {
        return null
    }

    val resolvedClass = type.resolve() ?: return null
    val className = resolvedClass.qualifiedName ?: return null

    // 检查是否是容器类型
    val isContainer = when (className) {
        "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
        "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
        "java.util.Collection", "java.util.Queue", "java.util.Deque",
        "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble",
        "java.util.stream.Stream", "java.util.concurrent.CompletableFuture",
        "com.google.common.collect.ImmutableList", "com.google.common.collect.ImmutableSet",
        "com.google.common.collect.ImmutableCollection" -> true
        "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap",
        "com.google.common.collect.ImmutableMap" -> true
        else -> false
    }

    if (!isContainer) {
        return null
    }

    val parameters = type.parameters
    if (parameters.isEmpty()) {
        return null
    }

    // 获取需要检查的元素类型列表
    val elementTypes = when (className) {
        // Map 类型检查所有参数（键和值）
        "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap",
        "com.google.common.collect.ImmutableMap" -> parameters.toList()
        // 其他容器只检查第一个参数
        else -> listOf(parameters[0])
    }

    // 对每个元素类型进行检查
    for (elementType in elementTypes) {
        // 如果元素类型本身是容器，递归检查
        val nestedResult = extractByteBufFromContainerElement(elementType, project)
        if (nestedResult != null) {
            return nestedResult
        }

        // 检查元素类型是否是 Class 类型（非基础类型、非数组）
        if (elementType is PsiClassType) {
            val elementClass = elementType.resolve()
            if (elementClass != null && !elementClass.isInterface) {
                // 检查这个类是否有 STREAM_CODEC 字段
                val codecField = elementClass.allFields.firstOrNull {
                    it.name == "STREAM_CODEC" &&
                    it.hasModifierProperty(PsiModifier.STATIC) &&
                    it.hasModifierProperty(PsiModifier.FINAL)
                }

                if (codecField != null) {
                    val codecType = extractByteBufFromType(codecField.type, project)
                    if (codecType != null) return codecType
                } else {
                    // 如果元素类没有 STREAM_CODEC，检查它是否实现了 StreamCodec
                    val streamCodecByteBuf = findStreamCodecByteBuf(elementClass, project)
                    if (streamCodecByteBuf != null) {
                        return streamCodecByteBuf
                    }
                }
            }
        }
    }

    return null
}

private fun extractByteBufFromType(type: PsiType, project: Project): PsiType? {
    if (type is PsiClassType) {
        val parameters2 = type.parameters
        if (parameters2.isNotEmpty()) {
            val firstParam = parameters2[0]
            if (isByteBufType(firstParam)) {
                return firstParam
            }
        }

        // 尝试通过 StreamCodec 类提取
        val codecClass = type.resolve()
        if (codecClass != null && isStreamCodecType(codecClass)) {
            val byteBufType = extractByteBufFromStreamCodec(codecClass, project)
            if (byteBufType != null && isByteBufType(byteBufType)) {
                return byteBufType
            }
        }
    }

    return null
}

/**
 * 判断类型是否为 ByteBuf 或其子类
 * 此方法为内部使用，已从 private 改为 internal 以便在 getFinalByteBufType 中使用
 */
internal fun isByteBufType(type: PsiType): Boolean {
    if (type !is PsiClassType) {
        return false
    }

    val psiClass = type.resolve() ?: return false
    val qualifiedName = psiClass.qualifiedName ?: return false

    // 直接匹配 ByteBuf
    if (qualifiedName == "io.netty.buffer.ByteBuf") {
        return true
    }

    // 检查是否继承自 ByteBuf
    var superClass = psiClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        val superName = superClass.qualifiedName
        if (superName == "io.netty.buffer.ByteBuf") {
            return true
        }
        superClass = superClass.superClass
        depth++
    }

    return false
}

/**
 * 查找类继承链中的 StreamCodec，返回其 ByteBuf 泛型类型
 */
private fun findStreamCodecByteBuf(psiClass: PsiClass, project: Project): PsiType? {
    // 检查当前类是否直接实现了 StreamCodec
    for (interfaceClass in psiClass.interfaces) {
        val byteBufType = extractByteBufFromStreamCodec(interfaceClass, project)
        if (byteBufType != null) {
            return byteBufType
        }
    }

    // 检查父类
    var superClass = psiClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        // 检查父类本身是否为 StreamCodec
        val byteBufType = extractByteBufFromStreamCodec(superClass, project)
        if (byteBufType != null) {
            return byteBufType
        }

        // 检查父类实现的接口
        for (interfaceClass in superClass.interfaces) {
            val byteBufTypeFromInterface = extractByteBufFromStreamCodec(interfaceClass, project)
            if (byteBufTypeFromInterface != null) {
                return byteBufTypeFromInterface
            }
        }
        superClass = superClass.superClass
        depth++
    }

    return null
}

/**
 * 从 StreamCodec 类中提取 ByteBuf 泛型参数
 * StreamCodec 的定义为：StreamCodec<B, V>
 * 其中 B 是 ByteBuf 类型，V 是值类型
 */
private fun extractByteBufFromStreamCodec(streamCodecClass: PsiClass, project: Project): PsiType? {
    if (!isStreamCodecType(streamCodecClass)) {
        return null
    }

    // 方法1: 检查 STREAM_CODEC 字段的类型（最直接的方式）
    val codecField = streamCodecClass.allFields.firstOrNull {
        it.name == "STREAM_CODEC" &&
        it.hasModifierProperty(PsiModifier.STATIC) &&
        it.hasModifierProperty(PsiModifier.FINAL)
    }
    if (codecField != null) {
        val codecType = codecField.type
        if (codecType is PsiClassType) {
            val bufType = extractByteBufFromClassType(codecType, streamCodecClass)
            if (bufType != null) return bufType
        }
    }

    // 方法2: 检查类自身是否使用 StreamCodec 作为泛型父类/接口
    val superTypes = streamCodecClass.superTypes
    for (superType in superTypes) {
        val bufType = extractByteBufFromGenericType(superType, streamCodecClass)
        if (bufType != null) return bufType
    }

    // 方法3: 检查 implements/extends 列表
    val extendsList = streamCodecClass.extendsListTypes
    for (extendsType in extendsList) {
        val bufType = extractByteBufFromGenericType(extendsType, streamCodecClass)
        if (bufType != null) return bufType
    }

    // 方法4: 检查父类
    var superClass = streamCodecClass.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        if (isStreamCodecType(superClass)) {
            val byteBufType = extractByteBufFromStreamCodec(superClass, project)
            if (byteBufType != null && isByteBufType(byteBufType)) {
                return byteBufType
            }
        }
        superClass = superClass.superClass
        depth++
    }

    // 方法5: 检查实现的接口
    for (interfaceClass in streamCodecClass.interfaces) {
        if (isStreamCodecType(interfaceClass)) {
            val byteBufType = extractByteBufFromStreamCodec(interfaceClass, project)
            if (byteBufType != null && isByteBufType(byteBufType)) {
                return byteBufType
            }
        }
    }

    // 如果仍然找不到，返回默认的 ByteBuf 类型
    return getByteBufType(project)
}

private fun extractByteBufFromGenericType(type: PsiType, context: PsiClass): PsiType? {
    if (type !is PsiClassType) return null
    val resolvedSuper = type.resolve()
    if (resolvedSuper != null && isStreamCodecType(resolvedSuper)) {
        return extractByteBufFromClassType(type, context)
    }
    return null
}

private fun extractByteBufFromClassType(type: PsiClassType, context: PsiClass): PsiType? {
    val parameters = type.parameters
    if (parameters.isNotEmpty()) {
        val firstParam = parameters[0]
        if (isByteBufType(firstParam)) {
            return firstParam
        }
        // 如果第一个参数是类型变量，尝试在类中查找其实际绑定
        if (firstParam is PsiClassType) {
            val resolved = firstParam.resolve()
            if (resolved is PsiTypeParameter) {
                val actualType = findTypeParameterBinding(resolved, context)
                if (actualType != null && isByteBufType(actualType)) {
                    return actualType
                }
            }
        }
    }
    return null
}

/**
 * 查找类型参数在类中的实际绑定类型
 * 例如：class MyCodec<B extends ByteBuf> implements StreamCodec<B, String>
 * 查找 B 的实际类型
 */
private fun findTypeParameterBinding(typeParameter: PsiTypeParameter, context: PsiClass): PsiType? {
    // 1. 检查类型参数的边界（extends 限制）
    val bounds = typeParameter.extendsListTypes
    for (bound in bounds) {
        if (isByteBufType(bound)) {
            return bound
        }
        // 如果边界本身是类型参数，递归查找
        val resolvedBound = bound.resolve()
        if (resolvedBound is PsiTypeParameter) {
            val actualType = findTypeParameterBinding(resolvedBound, context)
            if (actualType != null) {
                return actualType
            }
        }
    }

    // 2. 检查当前类的类型参数中是否有该参数的实例化
    val superTypes = context.supers
    for (superType in superTypes) {
        if (superType is PsiClassType) {
            val parameters = superType.parameters
            val typeParameters = superType.resolve()?.typeParameters ?: continue

            for (i in typeParameters.indices) {
                val param = typeParameters[i]
                if (param.name == typeParameter.name) {
                    val actualType = parameters.getOrNull(i)
                    if (actualType != null) {
                        if (isByteBufType(actualType)) {
                            return actualType
                        }
                        if (actualType is PsiClassType) {
                            val resolved = actualType.resolve()
                            if (resolved is PsiTypeParameter) {
                                return findTypeParameterBinding(resolved, context)
                            }
                        }
                    }
                }
            }
        }
    }

    // 3. 检查类的父类
    var superClass = context.superClass
    var depth = 0
    while (superClass != null && depth < 50) {
        val superTypeParams = superClass.typeParameters
        for (param in superTypeParams) {
            if (param.name == typeParameter.name) {
                val superTypesOfContext = context.supers
                for (superType in superTypesOfContext) {
                    if (superType is PsiClassType && superType.resolve() == superClass) {
                        val params = superType.parameters
                        val index = superTypeParams.indexOf(param)
                        if (index >= 0 && index < params.size) {
                            val actualType = params[index]
                            if (isByteBufType(actualType)) {
                                return actualType
                            }
                            if (actualType is PsiClassType) {
                                val resolved = actualType.resolve()
                                if (resolved is PsiTypeParameter) {
                                    return findTypeParameterBinding(resolved, context)
                                }
                            }
                        }
                    }
                }
            }
        }
        superClass = superClass.superClass
        depth++
    }

    return null
}

/**
 * 判断类是否为 StreamCodec 类型
 */
private fun isStreamCodecType(psiClass: PsiClass): Boolean {
    val qualifiedName = psiClass.qualifiedName ?: return false
    return qualifiedName == "net.minecraft.network.codec.StreamCodec"
}

/**
 * 获取 ByteBuf 的 PsiType
 */
private fun getByteBufType(project: Project): PsiType {
    val byteBufClass = getByteBufPsiClass(project)
    return byteBufClass?.let {
        JavaPsiFacade.getElementFactory(project).createType(it)
    } ?: error("ByteBuf class not found")
}

/**
 * 获取 ByteBuf 的 PsiClass
 */
private fun getByteBufPsiClass(project: Project): PsiClass? {
    val psiFacade = JavaPsiFacade.getInstance(project)
    return psiFacade.findClass("io.netty.buffer.ByteBuf", GlobalSearchScope.allScope(project))
}
