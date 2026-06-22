package io.github.tt432.k9tools

import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiField
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author TT432
 */
@Suppress("ConstPropertyName", "LocalVariableName", "DuplicatedCode")
class GenerateStreamCodecAction : AnAction() {
    companion object {
        const val StreamCodec = "net.minecraft.network.codec.StreamCodec"
        const val ByteBuf: String = "io.netty.buffer.ByteBuf"
    }

    @Suppress("UnstableApiUsage")
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val (editor, psiClass) = getPsiClass(event)

            if (editor == null || psiClass == null) return@runWriteCommandAction

            if (psiClass.isEnum && !psiClass.fields.any { it.name == "STREAM_CODEC" }) {
                val factory = PsiElementFactory.getInstance(project)
                val codecField = factory.createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, ${psiClass.name}> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(index -> ${psiClass.name}.values()[index], Enum::ordinal);",
                    psiClass
                )

                val styleManager = JavaCodeStyleManager.getInstance(project)
                psiClass.add(styleManager.shortenClassReferences(codecField))
            } else {
                val className = psiClass.name!!

                var fields = psiClass.allFields

                if (fields.any { it.name == "STREAM_CODEC" }) return@runWriteCommandAction

                fields = fields.filter { !it.hasModifier(JvmModifier.STATIC) }.toTypedArray()

                if (fields.size <= 6) {
                    serializeWithinSixFields(fields, className, psiClass, project)
                } else {
                    serializeMoreThanSixFields(fields, className, psiClass, project)
                }
            }
        }
    }

    private fun serializeWithinSixFields(
        fields: Array<out PsiField>,
        className: @NlsSafe String,
        psiClass: PsiClass,
        project: Project
    ) {
        val fieldsStr = StringBuilder()

        fields.forEach {
            fieldsStr.append(
                "    ${getStreamCodecRef(it.typeElement)},\n" +
                "    ${getGetter(className, it, getFieldAndGetterMethod(psiClass))},\n"
            )
        }

        val ByteBuf = getFinalByteBufType(fields, project)
        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, $className> STREAM_CODEC = $StreamCodec.composite(\n$fieldsStr$className::new\n);",
                    psiClass
                )
            )
        )
    }

    private fun serializeMoreThanSixFields(
        fields: Array<out PsiField>,
        className: @NlsSafe String,
        psiClass: PsiClass,
        project: Project
    ) {
        val decodeStr = StringBuilder()
        val decodeConstructStrBuilder = StringBuilder()
        val encodeStr = StringBuilder()

        fields.forEach {
            decodeStr.append(
                "        ${getTypeRef(it)} ${it.name} = ${getStreamCodecRef(it.typeElement)}.decode(buf);\n"
            )
            decodeConstructStrBuilder.append(
                "${it.name}, "
            )
            encodeStr.append(
                "        ${getStreamCodecRef(it.typeElement)}.encode(buf, ${getDirectGetterName(it, getFieldAndGetterMethod(psiClass))});\n"
            )
        }

        val decodeConstructStr = decodeConstructStrBuilder.substring(0, decodeConstructStrBuilder.length - 2)

        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, $className> STREAM_CODEC = new $StreamCodec<>() {\n" +
                    "    @java.lang.Override\n" +
                    "    public $className decode($ByteBuf buf) {\n" +
                    "$decodeStr" +
                    "        return new $className($decodeConstructStr);\n" +
                    "    }\n\n" +
                    "    @java.lang.Override\n" +
                    "    public void encode($ByteBuf buf, $className value) {\n" +
                    "$encodeStr" +
                    "    }\n" +
                    "};",
                    psiClass
                )
            )
        )
    }

    private fun getDirectGetterName(field: PsiField, map: Map<PsiField, String?>): String {
        return "value." + if (map.containsKey(field) && map[field] != null) "${map[field]}()" else field.name
    }
}
