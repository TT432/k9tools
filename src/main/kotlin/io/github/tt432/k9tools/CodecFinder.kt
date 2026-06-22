package io.github.tt432.k9tools

import com.intellij.psi.PsiTypeElement

private const val Codec: String = "com.mojang.serialization.Codec"
private const val ExtraCodecs: String = "net.minecraft.util.ExtraCodecs"
private const val ByteBufCodecs: String = "net.minecraft.network.codec.ByteBufCodecs"

fun getCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field), mapKey: Boolean = false): String {
    return if (vanillaCodecClasses.contains(typeName)) {
        "$Codec.${vanillaCodecFieldName[vanillaCodecClasses.indexOf(typeName)]}"
    } else if (vanillaKeywordCodec.contains(typeName)) {
        "$Codec.${vanillaCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
    } else if (vanillaExtraCodecs.containsKey(typeName)) {
        "$ExtraCodecs.${vanillaExtraCodecs[typeName]}"
    } else if (mapKey && vanillaMapKeySpecialCodecs.containsKey(typeName)) {
        "${vanillaMapKeySpecialCodecs[typeName]}"
    } else if (vanillaSpecialCodecs.containsKey(typeName)) {
        "${vanillaSpecialCodecs[typeName]}"
    } else when (typeName) {
        "java.util.List" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                val elementCodec = getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                return "$elementCodec\n.listOf()"
            }
            ""
        }

        "java.util.Map" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                val keyCodec = getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]), true)
                val valueCodec = getCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))
                return "$Codec.unboundedMap($keyCodec, $valueCodec)"
            }
            ""
        }

        "java.util.Optional" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                return getCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
            }
            ""
        }

        else -> "$typeName.CODEC"
    }
}

fun getStreamCodecRef(field: PsiTypeElement?, typeName: String = getTypeName(field), mapKey: Boolean = false): String {
    return if (vanillaStreamCodecClasses.contains(typeName)) {
        "$ByteBufCodecs.${vanillaStreamCodecFieldName[vanillaStreamCodecClasses.indexOf(typeName)]}"
    } else if (vanillaKeywordCodec.contains(typeName)) {
        "$ByteBufCodecs.${vanillaStreamCodecFieldName[vanillaKeywordCodec.indexOf(typeName)]}"
    } else if (mapKey && vanillaMapKeySpecialStreamCodecs.containsKey(typeName)) {
        "${vanillaMapKeySpecialStreamCodecs[typeName]}"
    } else if (vanillaSpecialStreamCodecs.containsKey(typeName)) {
        "${vanillaSpecialStreamCodecs[typeName]}"
    } else when (typeName) {
        "java.util.List" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                val elementCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                return "${elementCodec}.apply($ByteBufCodecs.list())"
            }
            ""
        }

        "java.util.Map" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                val keyCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]), true)
                val valueCodec = getStreamCodecRef(fieldGeneric[1], getTypeName(fieldGeneric[1]))
                return "$ByteBufCodecs.map(java.util.HashMap::new, $keyCodec, $valueCodec)"
            }
            ""
        }

        "java.util.Optional" -> {
            val fieldGeneric = getFieldGeneric(field)

            if (fieldGeneric.isNotEmpty()) {
                val innerCodec = getStreamCodecRef(fieldGeneric[0], getTypeName(fieldGeneric[0]))
                return "${innerCodec}.apply($ByteBufCodecs::optional)"
            }
            ""
        }

        else -> "$typeName.STREAM_CODEC"
    }
}

val vanillaCodecClasses = listOf(
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

private val vanillaStreamCodecClasses = listOf(
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
    "byte[]",
    "long[]"
)

val vanillaKeywordCodec = listOf(
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

private val vanillaStreamCodecFieldName = listOf(
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
    "BYTE_ARRAY",
    "LONG_ARRAY"
)

private val vanillaExtraCodecs = mapOf(
    Pair("com.google.gson.JsonElement", "JSON"),
    Pair("java.lang.Object", "JAVA"),
    Pair("net.minecraft.nbt.Tag", "NBT"),
    Pair("org.joml.Vector2fc", "VECTOR2F"),
    Pair("org.joml.Vector3fc", "VECTOR3F"),
    Pair("org.joml.Vector3ic", "VECTOR3I"),
    Pair("org.joml.Vector4fc", "VECTOR4F"),
    Pair("org.joml.Quaternionfc", "QUATERNIONF"),
    Pair("org.joml.AxisAngle4f", "AXISANGLE4F"),
    Pair("org.joml.Matrix4fc", "MATRIX4F"),
    Pair("java.util.regex.Pattern", "PATTERN"),
    Pair("java.time.Instant", "INSTANT_ISO8601"),
    Pair("net.minecraft.util.ExtraCodecs.TagOrElementLocation", "TAG_OR_ELEMENT_ID"),
    Pair("java.util.BitSet", "BIT_SET"),
    Pair("com.mojang.authlib.properties.Property", "PROPERTY"),
    Pair("java.net.URI", "UNTRUSTED_URI")
)

private val vanillaMapKeySpecialCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.STRING_CODEC"),
)

private val vanillaSpecialCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.CODEC"),
    Pair("net.minecraft.network.chat.Component", "net.minecraft.network.chat.ComponentSerialization.CODEC"),
)

private val vanillaMapKeySpecialStreamCodecs = mapOf<String, String>(
)

private val vanillaSpecialStreamCodecs = mapOf(
    Pair("java.util.UUID", "net.minecraft.core.UUIDUtil.STREAM_CODEC"),
    Pair("net.minecraft.network.chat.Component", "net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC"),
)
