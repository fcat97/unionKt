package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * A test-only extension, discovered through this module's test resources.
 *
 * It only reacts to markers whose names start with `Ext`, so every other test in the
 * module runs exactly as if no extension were installed.
 */
class TestExtension : UnionExtension {

    override fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> =
        when (union.markerName) {
            "ExtAnnotatedSpec" -> listOf(
                AnnotationSpec.builder(Deprecated::class).addMember("%S", "from extension").build(),
            )
            "ExtThrowsSpec" -> error("boom")
            else -> emptyList()
        }

    override fun generate(union: UnionInfo, env: ExtensionEnvironment) {
        val dependencies = Dependencies(aggregating = false, *union.sources.toTypedArray())
        when (union.markerName) {
            "ExtGeneratedSpec" -> FileSpec.builder(union.unionType.packageName, "ExtGeneratedExtra")
                .addFunction(
                    FunSpec.builder("describeFromExtension")
                        .receiver(union.unionType)
                        .returns(String::class)
                        .addStatement("return %S", "from extension")
                        .build(),
                )
                .build()
                .writeTo(env.codeGenerator, dependencies)

            "ExtInfoSpec" -> FileSpec.builder(union.unionType.packageName, "ExtInfoReport")
                .addFileComment("%L", describe(union))
                .build()
                .writeTo(env.codeGenerator, dependencies)
        }
    }

    private fun describe(union: UnionInfo): String = buildString {
        appendLine("union=${union.unionType} visibility=${union.visibility} marker=${union.marker.simpleName.asString()}")
        appendLine("typeParameters=${union.typeParameters.map { "${it.variance?.name?.lowercase()} ${it.name}" }}")
        union.members.forEach { member ->
            appendLine(
                "member ${member.simpleName} case=${member.caseClass} type=${member.typeName} " +
                    "typeParameter=${member.typeParameterName} " +
                    "declaration=${member.declaration?.qualifiedName?.asString()}",
            )
        }
    }
}
