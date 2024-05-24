package io.github.tt432.k9tools;

import com.intellij.lang.jvm.JvmModifier;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author TT432
 */
public class GenerateStreamCodecAction extends AnAction {
    private static final List<String> vanillaCodecClasses = List.of(
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
            "com.mojang.authlib.GameProfile"
    );

    private static final List<String> vanillaCodecFieldName = List.of(
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
            "GAME_PROFILE"
    );

    private static final List<String> vanillaKeywordCodec = List.of(
            "boolean",
            "byte",
            "short",
            "int",
            "long",
            "float",
            "double"
    );

    public static final String ByteBufCodecsName = "net.minecraft.network.codec.ByteBufCodecs";
    static final String streamCodecFieldName = "STREAM_CODEC";

    private String getCodecRef(PsiField field) {
        String typeName = getTypeName(field);

        if (vanillaCodecClasses.contains(typeName)) {
            return ByteBufCodecsName +"."+ vanillaCodecFieldName.get(vanillaCodecClasses.indexOf(typeName));
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return ByteBufCodecsName +"."+ vanillaCodecFieldName.get(vanillaKeywordCodec.indexOf(typeName));
        } else {
            return typeName + "." + streamCodecFieldName;
        }
    }

    private String getTypeName(PsiField field) {
        PsiTypeElement typeElement = field.getTypeElement();
        if (typeElement == null) return "";
        return getTypeName(typeElement);
    }

    private String getTypeName(PsiTypeElement element) {
        PsiJavaCodeReferenceElement ref = element.getInnermostComponentReferenceElement();
        if (ref == null) // is keyword ( boolean, int ... )
            return element.getText();
        return ref.getQualifiedName();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();

        if (project == null) return;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            var editor = event.getData(CommonDataKeys.EDITOR);
            var psiFile = event.getData(CommonDataKeys.PSI_FILE);

            if (editor == null || psiFile == null) return;

            var element = psiFile.findElementAt(editor.getCaretModel().getOffset());

            var psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

            if (psiClass == null) return;

            PsiField[] fields = psiClass.getAllFields();

            for (PsiField field : fields) {
                if (field.getName().equals(streamCodecFieldName)) {
                    return;
                }
            }

            String className = psiClass.getName();
            StringBuilder fieldsStr = new StringBuilder();

            for (PsiField field : fields) {
                if (field.hasModifier(JvmModifier.STATIC)) {
                    continue;
                }

                fieldsStr.append("""
                        %s,
                        %s::%s,
                        """.formatted(getCodecRef(field), className, field.getName()));
            }

            var codecField = PsiElementFactory.getInstance(project).createFieldFromText("""
                            public static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, %s> %s = net.minecraft.network.codec.StreamCodec.composite(
                            %s%s::new
                            );
                            """.formatted(className, streamCodecFieldName, fieldsStr.toString(), className),
                    psiClass);

            psiClass.add(JavaCodeStyleManager.getInstance(project).shortenClassReferences(codecField));
        });
    }
}
