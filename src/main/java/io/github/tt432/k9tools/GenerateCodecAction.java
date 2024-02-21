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
public class GenerateCodecAction extends AnAction {
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

            if (psiClass.isEnum()) {
                PsiElementFactory factory = PsiElementFactory.getInstance(project);

                var codecField = factory.createFieldFromText(
                        "public static final com.mojang.serialization.Codec<%s> CODEC = net.minecraft.util.StringRepresentable.fromEnum(%s::values);"
                                .formatted(psiClass.getName(), psiClass.getName())
                        , psiClass);

                JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
                psiClass.add(styleManager.shortenClassReferences(codecField));

                var stringRepresentableImpl = factory.createMethodFromText("""
                        @Override
                        @org.jetbrains.annotations.NotNull
                        public String getSerializedName() {
                            return name();
                        }
                        """, psiClass);

                psiClass.add(styleManager.shortenClassReferences(stringRepresentableImpl));

                PsiReferenceList implementsList = psiClass.getImplementsList();
                int offset;

                if (implementsList != null) {
                    offset = implementsList.getTextOffset();
                } else {
                    PsiIdentifier id = psiClass.getNameIdentifier();

                    if (id != null) {
                        offset = id.getTextOffset();
                    } else {
                        offset = -1;
                    }
                }

                if (offset > 0) {
                    editor.getDocument().insertString(offset, " implements StringRepresentable ");
                }
            } else {
                processRecordOrClass(psiClass, project);
            }
        });
    }

    private void processRecordOrClass(PsiClass psiClass, Project project) {
        PsiField[] fields = psiClass.getAllFields();

        for (PsiField field : fields) {
            if (field.getName().equals("CODEC")) {
                return;
            }
        }

        StringBuilder fieldsStr = new StringBuilder();

        for (PsiField field : fields) {
            if (field.hasModifier(JvmModifier.STATIC)) {
                continue;
            }

            if (!fieldsStr.toString().isBlank())
                fieldsStr.append(",\n");
            fieldsStr.append("%s.%s.forGetter(o -> o.%s)".formatted(getCodecRef(field), getFieldOf(field), field.getName()));
        }

        String className = psiClass.getName();

        var codecField = PsiElementFactory.getInstance(project).createFieldFromText("""
                        public static final com.mojang.serialization.Codec<%s> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(ins -> ins.group(
                        %s
                        ).apply(ins, %s::new));
                        """.formatted(className, fieldsStr.toString(), className),
                psiClass);

        psiClass.add(JavaCodeStyleManager.getInstance(project).shortenClassReferences(codecField));
    }

    private static final List<String> vanillaCodecClasses = List.of(
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

    private static final List<String> vanillaCodecFieldName = List.of(
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
    );

    private String getCodecRef(PsiField field) {
        return getCodecRef(field, getTypeName(field));
    }

    private String getCodecRef(PsiField field, String typeName) {
        if (vanillaCodecClasses.contains(typeName)) {
            return "com.mojang.serialization.Codec." + vanillaCodecFieldName.get(vanillaCodecClasses.indexOf(typeName));
        } else if (vanillaKeywordCodec.contains(typeName)) {
            return "com.mojang.serialization.Codec." + vanillaCodecFieldName.get(vanillaKeywordCodec.indexOf(typeName));
        } else if (typeName.equals("java.util.List")) {
            var fieldGeneric = getFieldGeneric(field);

            if (fieldGeneric.length > 0) {
                return getCodecRef(field, getTypeName(fieldGeneric[0])) + ".listOf()";
            }
        } else if (typeName.equals("java.util.Map")) {
            var fieldGeneric = getFieldGeneric(field);

            if (fieldGeneric.length > 1) {
                return "com.mojang.serialization.Codec.unboundedMap(%s, %s)"
                        .formatted(getCodecRef(field, getTypeName(fieldGeneric[0])),
                                getCodecRef(field, getTypeName(fieldGeneric[1])));
            }
        } else {
            return typeName + ".CODEC";
        }

        return "";
    }

    private String getFieldOf(PsiField field) {
        boolean nullable = false;

        for (PsiAnnotation annotation : field.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name == null) continue;
            String[] split = name.split("\\.");

            if (split.length > 0 && split[split.length - 1].equals("Nullable")) {
                nullable = true;
                break;
            }
        }

        if (nullable) {
            return "optionalFieldOf(\"%s\", %s)".formatted(field.getName(), getDefaultValue(field));
        } else {
            return "fieldOf(\"%s\")".formatted(field.getName());
        }
    }

    private String getDefaultValue(PsiField field) {
        String typeName = getTypeName(field);

        int idx = vanillaCodecClasses.indexOf(typeName) + vanillaKeywordCodec.indexOf(typeName) + 1;

        return switch (idx) {
            case 0 -> "false";
            case 1 -> "(byte) 0";
            case 2 -> "(short) 0";
            case 3 -> "0";
            case 4 -> "0L";
            case 5 -> "0.0F";
            case 6 -> "0.0D";
            case 7 -> "\"\"";
            default -> "null";
        };
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

    private PsiTypeElement[] getFieldGeneric(PsiField field) {
        var type = field.getTypeElement();
        if (type == null) return new PsiTypeElement[0];
        PsiJavaCodeReferenceElement ref = type.getInnermostComponentReferenceElement();
        if (ref == null) return new PsiTypeElement[0];
        PsiReferenceParameterList parameterList = ref.getParameterList();
        if (parameterList == null) return new PsiTypeElement[0];
        return parameterList.getTypeParameterElements();
    }
}
