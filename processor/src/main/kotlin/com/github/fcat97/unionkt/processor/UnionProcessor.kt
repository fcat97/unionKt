package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier

/**
 * Generates a sealed-interface union for every `@Union`-annotated Spec marker.
 *
 * This class validates the marker itself; [MemberResolver] works out the cases and
 * [UnionWriter] emits the file. Every precondition is reported through
 * [KSPLogger.error] and the marker is skipped. Nothing is ever guessed: if the
 * processor cannot determine the union name or its members with certainty, it fails
 * the compilation instead.
 */
internal class UnionProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val memberResolver = MemberResolver(logger)
    private val writer = UnionWriter(codeGenerator)

    /** Guards against two markers in one package resolving to the same union. */
    private val generated = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(UNION_ANNOTATION_NAME).toList()
        // The two-argument validate(predicate, enableNewFeatures) only exists in KSP
        // 2.3.12+. This single-argument form is present in every 2.x release, so the
        // processor stays binary-compatible across the whole line.
        @Suppress("DEPRECATION")
        val (resolvable, deferred) = symbols.partition { it.validate() }

        resolvable.forEach { symbol ->
            if (symbol is KSClassDeclaration) {
                generateUnion(symbol)
            } else {
                // @Union targets CLASS, so this is unreachable in practice.
                logger.error("@Union may only be applied to an interface declaration.", symbol)
            }
        }

        return deferred
    }

    private fun generateUnion(marker: KSClassDeclaration) {
        val markerName = marker.simpleName.asString()

        if (marker.classKind != ClassKind.INTERFACE) {
            val kind = marker.classKind.type
            val article = if (kind.first() in "aeiou") "an" else "a"
            logger.error(
                "@Union may only be applied to an interface, but '$markerName' is $article " +
                    "$kind. The marker exists purely to name the union; " +
                    "declare it as 'interface $markerName'.",
                marker,
            )
            return
        }

        if (marker.typeParameters.isNotEmpty()) {
            logger.error(
                "@Union marker '$markerName' declares type parameters, which the generated " +
                    "union cannot carry. Remove them.",
                marker,
            )
            return
        }

        val unionName = unionNameOf(markerName) ?: run {
            logger.error(
                "@Union marker '$markerName' must be named '<Union>$SPEC_SUFFIX' — KSP cannot " +
                    "modify an existing declaration, so it generates the union from the marker's " +
                    "name minus the '$SPEC_SUFFIX' suffix. Rename it to e.g. '${markerName}$SPEC_SUFFIX'.",
                marker,
            )
            return
        }

        val packageName = marker.packageName.asString()
        if (packageName.isBlank()) {
            logger.error(
                "@Union marker '$markerName' must live in a named package; the generated union " +
                    "is emitted into the marker's package and the default package is not addressable.",
                marker,
            )
            return
        }

        val qualifiedUnionName = "$packageName.$unionName"
        if (!generated.add(qualifiedUnionName)) {
            logger.error(
                "@Union marker '$markerName' would generate '$qualifiedUnionName', which another " +
                    "marker in the same package already generates. Rename one of them.",
                marker,
            )
            return
        }

        val members = memberResolver.resolve(marker, markerName) ?: return

        // Computed last so the private -> internal warning is only emitted for a marker
        // that actually produces a union.
        val visibility = unionVisibilityOf(marker, markerName) ?: return

        writer.write(
            model = UnionModel(
                markerName = markerName,
                unionType = ClassName(packageName, unionName),
                visibility = visibility,
                members = members,
            ),
            sources = listOfNotNull(marker.containingFile),
        )
    }

    /**
     * Maps the marker's visibility onto the union.
     *
     * `private` is deliberately promoted to `internal`: the union is emitted into its
     * own file (KSP can only create files, never extend an existing one), and a
     * `private` top-level declaration there would be invisible to the very file that
     * declared the marker.
     */
    private fun unionVisibilityOf(marker: KSClassDeclaration, markerName: String): KModifier? =
        when (val visibility = marker.getVisibility()) {
            Visibility.PUBLIC -> KModifier.PUBLIC
            Visibility.INTERNAL -> KModifier.INTERNAL
            Visibility.PRIVATE -> {
                logger.warn(
                    "@Union marker '$markerName' is private; the generated union is emitted as " +
                        "'internal' because a private top-level declaration in the generated file " +
                        "would be invisible to '${marker.containingFile?.fileName ?: "the marker's file"}'.",
                    marker,
                )
                KModifier.INTERNAL
            }

            else -> {
                logger.error(
                    "@Union marker '$markerName' has unsupported visibility " +
                        "'${visibility.name.lowercase()}'. Use public, internal or private.",
                    marker,
                )
                null
            }
        }
}
