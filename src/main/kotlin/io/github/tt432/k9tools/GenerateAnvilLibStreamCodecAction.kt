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
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * @author QiuShui1012
 */
@Suppress("ConstPropertyName", "LocalVariableName", "DuplicatedCode")
class GenerateAnvilLibStreamCodecAction : AnAction() {
    companion object {
        const val StreamCodec = "net.minecraft.network.codec.StreamCodec"
        const val StreamCodecUtil = "dev.anvilcraft.lib.v2.codec.StreamCodecUtil"
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
                    "public static final $StreamCodec<$ByteBuf, ${psiClass.name}> STREAM_CODEC = $StreamCodecUtil.enumStreamCodec(${psiClass.name}.class);",
                    psiClass
                )

                val styleManager = JavaCodeStyleManager.getInstance(project)
                psiClass.add(styleManager.shortenClassReferences(codecField))
            } else {
                val className = psiClass.name!!

                var fields = psiClass.allFields

                if (fields.any { it.name == "STREAM_CODEC" }) return@runWriteCommandAction

                fields = fields.filter { !it.hasModifier(JvmModifier.STATIC) }.toTypedArray()

                serialize(fields, className, psiClass, project)
            }
        }
    }

    private fun serialize(
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
        val CompositeSource = if (fields.size <= 6) StreamCodec else StreamCodecUtil
        psiClass.add(
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(
                PsiElementFactory.getInstance(project).createFieldFromText(
                    "public static final $StreamCodec<$ByteBuf, $className> STREAM_CODEC = $CompositeSource.composite(\n$fieldsStr$className::new\n);",
                    psiClass
                )
            )
        )
    }

    private fun getStreamCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field)): String {
        for ((types, streamCodec) in anvillibStreamCodecs) {
            if (types.contains(typeName)) {
                return "$StreamCodecUtil.$streamCodec"
            }
        }
        return getStreamCodecRef(field, typeName, false)
    }
}

private val anvillibStreamCodecs = mapOf(
    Pair(listOf("net.minecraft.world.level.block.state.BlockState"), "BLOCK_STATE"),
    Pair(listOf("net.minecraft.world.entity.EntityType"), "ENTITY"),
    Pair(listOf("java.lang.Character", "char"), "CHAR"),
    Pair(listOf("net.minecraft.world.phys.Vec3"), "VEC3"),
    Pair(listOf("net.minecraft.core.Vec3i"), "VEC3I"),
    Pair(listOf("net.minecraft.world.level.storage.loot.providers.number.NumberProvider"), "NUMBER_PROVIDER"),
    Pair(listOf("net.minecraft.util.ExtraCodecs.TagOrElementLocation"), "TAG_OR_ELEMENT_LOCATION"),
    Pair(listOf("net.minecraft.advancements.criterion.EntityTypePredicate"), "ENTITY_TYPE_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.DistancePredicate"), "DISTANCE_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.MovementPredicate"), "MOVEMENT_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.LocationPredicate.PositionPredicate"), "POSITION_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.LightPredicate"), "LIGHT_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.FluidPredicate"), "FLUID_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.LocationPredicate"), "LOCATION_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.EntityPredicate.LocationWrapper"), "LOCATION_WRAPPER"),
    Pair(listOf("net.minecraft.advancements.criterion.MobEffectsPredicate.MobEffectInstancePredicate"), "MOB_EFFECT_INSTANCE_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.MobEffectsPredicate"), "MOB_EFFECTS_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.NbtPredicate"), "NBT_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.EntityFlagsPredicate"), "ENTITY_FLAGS_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.ItemPredicate"), "ITEM_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.EntityEquipmentPredicate"), "ENTITY_EQUIPMENT_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.EntitySubPredicate"), "ENTITY_SUB_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.SlotsPredicate"), "SLOTS_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.EntityPredicate"), "ENTITY_PREDICATE"),
    Pair(listOf("net.minecraft.advancements.criterion.DamageSourcePredicate"), "DAMAGE_SOURCE_PREDICATE"),
)
