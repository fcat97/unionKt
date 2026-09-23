package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.github.fcat97.unionkt.api.UnionMemberInfo
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.WildcardTypeName

/**
 * Attaches an untagged kotlinx.serialization JSON serializer to every union.
 *
 * Discovered through `META-INF/services` when this artifact is on the `ksp` configuration.
 */
public class KotlinxSerializationExtension : UnionExtension {

    /** Unions that failed validation: they get neither the annotation nor a serializer. */
    private val rejected = mutableSetOf<ClassName>()

    /** Whether kotlinx.serialization is on the compile classpath; checked once per compilation. */
    private var runtimeAvailable: Boolean? = null

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
    private fun validate(union: UnionInfo, env: ExtensionEnvironment): Boolean {
        if (!runtimeAvailable(env)) return false

        var valid = true
        union.members.filter { it.typeParameterName == null }.forEach { member ->
            val problem = problemWith(member) ?: return@forEach
            env.logger.error("@Union on '${union.markerName}' cannot serialize member $problem", union.marker)
            valid = false
        }
        return valid
    }

    private fun runtimeAvailable(env: ExtensionEnvironment): Boolean = runtimeAvailable ?: run {
        val name = env.resolver.getKSNameFromString(SERIALIZABLE.canonicalName)
        val available = env.resolver.getClassDeclarationByName(name) != null
        if (!available) {
            env.logger.error(
                "serialization-kotlinx is installed, but kotlinx-serialization-core is not on the " +
                    "classpath. Add the kotlinx-serialization-json dependency.",
            )
        }
        available.also { runtimeAvailable = it }
    }

    /** Why a concrete member cannot be serialized, or null when it can. */
    private fun problemWith(member: UnionMemberInfo): String? {
        val type = member.typeName
        if (type is ParameterizedTypeName && type.typeArguments.any { it is WildcardTypeName }) {
            val stars = type.typeArguments.joinToString { "*" }
            return "'${member.simpleName}<$stars>': a class literal cannot say its element type. " +
                "Wrap it in a @Serializable class."
        }
        val declaration = member.declaration ?: return null
        if (isSerializable(declaration)) return null
        return "'${declaration.qualifiedName?.asString() ?: member.simpleName}': it is not @Serializable. " +
            "Annotate it, or wrap it in a @Serializable class."
    }

    /**
     * `@Serializable` (with or without `with =`), an enum, or a `kotlin.*` type. Serializers
     * registered only at runtime (contextual, `@file:UseSerializers`) are invisible here.
     */
    private fun isSerializable(declaration: KSClassDeclaration): Boolean {
        val packageName = declaration.packageName.asString()
        return declaration.classKind == ClassKind.ENUM_CLASS ||
            packageName == "kotlin" ||
            packageName.startsWith("kotlin.") ||
            declaration.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == SERIALIZABLE.canonicalName
            }
    }

    internal companion object {
        val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
    }
}

/** `com.example.Result` → `com.example.ResultSerializer`. */
internal fun serializerClassName(union: UnionInfo): ClassName =
    ClassName(union.unionType.packageName, union.unionType.simpleName + "Serializer")
