package io.github.tt432.k9tools

import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression

/**
 * @author QiuShui1012 & TT432
 */

fun getFieldAndGetterMethod(psiClass: PsiClass): Map<PsiField, String?> {
    val isRecord = psiClass.isRecord
    return psiClass.allFields.associateWith { field ->
        if (isRecord) {
            field.name
        } else {
            findGetterMethodName(psiClass, field)
        }
    }
}

private fun findGetterMethodName(psiClass: PsiClass, field: PsiField): String? {
    // 1. 检查手动编写的 Getter 方法
    val possibleNames = getPossibleGetterNames(psiClass, field)

    return possibleNames.firstOrNull { name ->
        psiClass.findMethodsByName(name, false).any { method ->
            method.returnTypeElement?.type?.equals(field.type) == true
        }
    }
}

private fun getPossibleGetterNames(psiClass: PsiClass, field: PsiField): List<String> {
    val fieldName = field.name
    val fieldType = field.type.canonicalText
    val isBoolean = fieldType == "boolean" || fieldType == "Boolean" || fieldType == "java.lang.Boolean"

    // 检查是否有 @Accessors 注解
    val accessors = psiClass.modifierList?.annotations?.firstOrNull {
        it.resolveAnnotationType()?.qualifiedName == "lombok.experimental.Accessors"
    }

    val names = mutableListOf<String>()

    if (accessors != null) {
        // 检查 fluent 模式
        val fluent = accessors.findAttributeValue("fluent") as? PsiLiteralExpression
        if (fluent?.value == true) {
            names.add(fieldName)
        } else {
            // 检查 prefix 配置
            val prefixAttr = accessors.findAttributeValue("prefix")
            if (prefixAttr is PsiArrayInitializerMemberValue) {
                prefixAttr.initializers.forEach { init ->
                    if (init is PsiLiteralExpression) {
                        val prefix = init.value?.toString() ?: return@forEach
                        if (fieldName.startsWith(prefix)) {
                            val baseName = fieldName.removePrefix(prefix)
                            val getterName = if (isBoolean) "is${baseName.replaceFirstChar { it.uppercase() }}"
                            else "get${baseName.replaceFirstChar { it.uppercase() }}"
                            names.add(getterName)
                        }
                    }
                }
            }
        }
    }

    // 如果没有找到 Accessors 配置，使用标准命名
    if (names.isEmpty()) {
        val getterName = if (isBoolean) "is${fieldName.replaceFirstChar { it.uppercase() }}"
        else "get${fieldName.replaceFirstChar { it.uppercase() }}"
        names.add(getterName)
    }

    return names
}

fun getGetter(className: String, field: PsiField, map: Map<PsiField, String?>): String {
    return if (map.containsKey(field) && map[field] != null) {
        "$className::${map[field]}"
    } else {
        "o -> o.${field.name}"
    }
}
