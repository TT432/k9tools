package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author QiuShui1012
 */
@Suppress("ConstPropertyName", "DuplicatedCode")
class GenerateAnvilLibMapCodecAction : AnAction() {
    companion object {
        private const val MapCodec: String = "com.mojang.serialization.MapCodec"
        private const val CodecUtil: String = "dev.anvilcraft.lib.v2.codec.CodecUtil"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event)

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum) {
                val action = event.actionManager.getAction("Generate.AnvilLibCodec")
                event.actionManager.tryToExecute(action, null, null, null, true)
            } else {
                processRecordOrClass(psiClass, project)
            }

        }
    }

    @Suppress("UnstableApiUsage")
    private fun processRecordOrClass(psiClass: PsiClass, project: Project) {
        val fields = psiClass.allFields

        if (fields.any { it.name == "CODEC" }) return

        val fieldsStr = StringBuilder()
        val className = psiClass.name!!

        fields.filter { !it.hasModifier(JvmModifier.STATIC) }.forEach {
            fieldsStr.append(
                """
                    ${getCodecRef(it.typeElement)}
                    .${getFieldOf(it)}
                    .forGetter(${
                        getGetter(
                            className,
                            it,
                            getFieldAndGetterMethod(psiClass)
                        )
                    }),
                    
                """.trimIndent()
            )
        }

        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $MapCodec<$className> CODEC = $CodecUtil.mapCodec(\n$fieldsStr$className::new\n);",
                    psiClass
                )
            )
        )
    }

    private fun getFieldOf(field: PsiField): String {
        field.annotations.forEach { it ->
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
