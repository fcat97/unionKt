package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName

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

        val typeParameters = resolveTypeParameters(marker, markerName) ?: return
        val members = memberResolver.resolve(marker, markerName, typeParameters) ?: return

        // Computed last so the private -> internal warning is only emitted for a marker
        // that actually produces a union.
        val visibility = unionVisibilityOf(marker, markerName) ?: return

        writer.write(
            model = UnionModel(
                markerName = markerName,
                unionType = ClassName(packageName, unionName),
                visibility = visibility,
                members = members,
                typeParameters = typeParameters,
            ),
            sources = listOfNotNull(marker.containingFile),
        )
    }

    /**
     * The marker's type parameters, which become both the union's type parameters and
     * cases. Null after reporting every problem.
     */
    private fun resolveTypeParameters(
        marker: KSClassDeclaration,
        markerName: String,
    ): List<UnionTypeParameter>? {
        val resolver = marker.typeParameters.toTypeParameterResolver()
        val names = marker.typeParameters.map { it.name.asString() }.toSet()
        var valid = true

        val parameters = marker.typeParameters.map { parameter ->
            val name = parameter.name.asString()

            if (parameter.variance == Variance.CONTRAVARIANT) {
                logger.error(
                    "@Union marker '$markerName' type parameter '$name' is declared 'in', but a " +
                        "union case stores a $name, which only an 'out' or invariant parameter " +
                        "allows. Remove 'in'.",
                    marker,
                )
                valid = false
            }

            val bounds = parameter.toTypeVariableName(resolver).bounds.filter { it != NULLABLE_ANY }
            val foreign = bounds.flatMap { it.referencedTypeVariables() }.filter { it != name && it in names }.distinct()
            if (foreign.isNotEmpty()) {
                logger.error(
                    "@Union marker '$markerName' type parameter '$name' has a bound that refers to " +
                        "${foreign.joinToString { "'$it'" }}. Each case class declares only its own " +
                        "type parameter, so such a bound cannot be expressed. Remove the reference.",
                    marker,
                )
                valid = false
            }

            UnionTypeParameter(name = name, bounds = bounds)
        }

        return parameters.takeIf { valid }
    }

    /** Names of every type variable mentioned anywhere inside this type. */
    private fun TypeName.referencedTypeVariables(): Set<String> = when (this) {
        // Deliberately not recursing into a variable's own bounds: `T : Comparable<T>` would loop.
        is TypeVariableName -> setOf(name)
        is ParameterizedTypeName -> typeArguments.flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        is WildcardTypeName -> (inTypes + outTypes).flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        is LambdaTypeName ->
            (listOfNotNull(receiver) + parameters.map { it.type } + returnType)
                .flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        else -> emptySet()
    }

    private companion object {
        val NULLABLE_ANY: TypeName = ANY.copy(nullable = true)
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
