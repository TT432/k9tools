package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
class GenerateStreamCodecAction : AnAction() {
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
            "net.minecraft.nbt.Tag",
            "net.minecraft.nbt.CompoundTag",
            "org.joml.Vector3f",
            "org.joml.Quaternionf",
            "com.mojang.authlib.properties.PropertyMap",
            "com.mojang.authlib.GameProfile",
            "byte[]"
        )

        private val vanillaCodecFieldName = listOf(
            "BOOL",
            "BYTE",
            "SHORT",
            "VAR_INT",
            "VAR_LONG",
            "FLOAT",
            "DOUBLE",
            "STRING_UTF8",
            "TAG",
            "COMPOUND_TAG",
            "VECTOR3F",
            "QUATERNIONF",
            "GAME_PROFILE_PROPERTIES",
            "GAME_PROFILE",
            "BYTE_ARRAY"
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

        const val ByteBufCodecsName: String = "net.minecraft.network.codec.ByteBufCodecs"
        const val StreamCodec = "net.minecraft.network.codec.StreamCodec"
    }

    private fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        if (vanillaCodecClasses.contains(typeName)) {
            return "$ByteBufCodecsName.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "$ByteBufCodecsName.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
        } else when (typeName) {
            "java.util.List" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$ByteBufCodecsName.collection(java.util.ArrayList::new, ${
                        getCodecRef(
                            fieldGeneric[0],
                            getTypeName(fieldGeneric[0])
                        )
                    })"
                }
            }

            "java.util.Map" -> {
                val fieldGeneric = getFieldGeneric(field)

                if (fieldGeneric.isNotEmpty()) {
                    return "$ByteBufCodecsName.map(java.util.HashMap::new, ${
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
                    return "$ByteBufCodecsName.optional(${getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))})"
                }
            }

            else -> {
                return "$typeName.STREAM_CODEC"
            }
        }

        return ""
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event);

            if (editor == null || psiClass == null) return@runWriteCommandAction

            val fields = psiClass.allFields

            if (fields.any { it.name == "STREAM_CODEC" }) return@runWriteCommandAction

            val className = psiClass.name!!
            val fieldsStr = StringBuilder()

            fields.filter { !it.hasModifier(JvmModifier.STATIC) }.forEach {
                fieldsStr.append(
                    "    ${getCodecRef(it.typeElement)},\n" +
                            "    ${getGetterName(className, it, getFieldAndGetterMethod(psiClass))},\n"
                )
            }

            psiClass.add(
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                    PsiElementFactory.getInstance(project).createFieldFromText(
                        "public static final $StreamCodec<io.netty.buffer.ByteBuf, $className> STREAM_CODEC = $StreamCodec.composite(\n" +
                                "$fieldsStr    $className::new\n" +
                                ");",
                        psiClass
                    )
                )
            )
        }
    }
}
