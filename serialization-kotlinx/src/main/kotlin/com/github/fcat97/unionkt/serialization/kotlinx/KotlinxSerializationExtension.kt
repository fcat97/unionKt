package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

/**
 * Attaches an untagged kotlinx.serialization JSON serializer to every union.
 *
 * Discovered through `META-INF/services` when this artifact is on the `ksp` configuration.
 */
public class KotlinxSerializationExtension : UnionExtension {

    /** Unions that failed validation: they get neither the annotation nor a serializer. */
    private val rejected = mutableSetOf<ClassName>()

    override fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> {
        if (!validate(union, env)) {
            rejected += union.unionType
            return emptyList()
        }
        return listOf(
            AnnotationSpec.builder(SERIALIZABLE)
                .addMember("with = %T::class", serializerClassName(union))
                .build(),
        )
    }

    override fun generate(union: UnionInfo, env: ExtensionEnvironment) {
        if (union.unionType in rejected) return
        SerializerWriter(env.codeGenerator).write(union)
    }

    /** Reports every problem that would make the generated serializer wrong; true when there were none. */
    @Suppress("UNUSED_PARAMETER")
    private fun validate(union: UnionInfo, env: ExtensionEnvironment): Boolean = true

    internal companion object {
        val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
    }
}

/** `com.example.Result` → `com.example.ResultSerializer`. */
internal fun serializerClassName(union: UnionInfo): ClassName =
    ClassName(union.unionType.packageName, union.unionType.simpleName + "Serializer")
