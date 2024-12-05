package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
class GenerateCodecAction : AnAction() {
    companion object {
        private val vanillaCodecClasses = listOf(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.String",
            "java.nio.ByteBuffer",
            "java.util.stream.IntStream",
            "java.util.stream.LongStream"
        )

        private val vanillaKeywordCodec = listOf(
            "boolean",
            "byte",
            "short",
            "int",
            "long",
            "float",
            "double"
        )

        private val vanillaCodecFieldName = listOf(
            "BOOL",
            "BYTE",
            "SHORT",
            "INT",
            "LONG",
            "FLOAT",
            "DOUBLE",
            "STRING",
            "BYTE_BUFFER",
            "INT_STREAM",
            "LONG_STREAM"
        )

        private const val Codec: String = "com.mojang.serialization.Codec"
        private const val StringRepresentable: String = "net.minecraft.util.StringRepresentable"
        private const val NotNull: String = "org.jetbrains.annotations.NotNull"
        private const val RecordCodecBuilder: String = "com.mojang.serialization.codecs.RecordCodecBuilder"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event);

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum) {
                val factory = PsiElementFactory.getInstance(project)

                val codecField = factory.createFieldFromText(
                    "public static final $Codec<${psiClass.name}> CODEC = $StringRepresentable.fromEnum(${psiClass.name}::values);",
                    psiClass
                )

                val styleManager = JavaCodeStyleManager.getInstance(project)
                psiClass.add(styleManager.shortenClassReferences(codecField))

                val stringRepresentableImpl = factory.createMethodFromText(
                    "    @Override\n" +
                            "    @$NotNull\n" +
                            "    public String getSerializedName() {\n" +
                            "        return name();\n" +
                            "    }",
                    psiClass
                )

                psiClass.add(styleManager.shortenClassReferences(stringRepresentableImpl))

                val offset: Int = psiClass.implementsList?.textOffset ?: (psiClass.nameIdentifier?.textOffset ?: -1)

                if (offset > 0) {
                    editor.document.insertString(offset, " implements StringRepresentable ")
                }
            } else {
                processRecordOrClass(psiClass, project)
            }
        }
    }

    private fun processRecordOrClass(psiClass: PsiClass, project: Project) {
        val fields = psiClass.allFields

        if (fields.any { it.name == "CODEC" }) return

        val fieldsStr = StringBuilder()
        val className = psiClass.name!!

        fields.filter { !it.hasModifier(JvmModifier.STATIC) }.forEach {
            fieldsStr.append(
                "    ${getCodecRef(it.typeElement)}.${getFieldOf(it)}.forGetter(${
                    getGetterName(
                        className,
                        it,
                        getFieldAndGetterMethod(psiClass)
                    )
                }),\n"
            )
        }

        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $Codec<$className> CODEC = $RecordCodecBuilder.create(ins -> ins.group(\n" +
                            "${fieldsStr.toString().removeSuffix(",\n") + "\n"}).apply(ins, $className::new));",
                    psiClass
                )
            )
        )
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaCodecClasses.contains(typeName)) {
            return "$Codec.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$Codec.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
        } else when (typeName) {
            "java.util.List" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "${getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))}.listOf()"
                }
            }

            "java.util.Map" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$Codec.unboundedMap(${
                        getCodecRef(
                            fieldGeneric[0],
                            getTypeName(fieldGeneric[0])
                        )
                    }, ${getCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))})"
                }
            }

            "java.util.Optional" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                }
            }

            else -> {
                return "$typeName.CODEC"
            }
        }

        return ""
    }

    private fun getFieldOf(field: PsiField): String {
        field.annotations.forEach {
            val name = it.qualifiedName ?: return@forEach
            val split = name.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            if (split.isNotEmpty() && split[split.size - 1] == "Nullable") {
                return "optionalFieldOf(\"${field.name}\", ${getDefaultValue(field)})"
            }
        }

        return when (getTypeName(field)) {
            "java.util.Optional" -> "optionalFieldOf(\"${field.name}\")"
            else -> "fieldOf(\"${field.name}\")"
        }
    }

    private fun getDefaultValue(field: PsiField): String {
        val typeName = getTypeName(field)

        return when (vanillaCodecClasses.indexOf(typeName) + vanillaKeywordCodec.indexOf(typeName) + 1) {
            0 -> "false"
            1 -> "(byte) 0"
            2 -> "(short) 0"
            3 -> "0"
            4 -> "0L"
            5 -> "0.0F"
            6 -> "0.0D"
            7 -> "\"\""
            else -> "null"
        }
    }
}
