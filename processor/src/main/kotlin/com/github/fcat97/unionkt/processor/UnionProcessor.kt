package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.Union
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a sealed-interface union for every `@Union`-annotated Spec marker.
 *
 * Every precondition below is reported through [KSPLogger.error] and the marker
 * is skipped. Nothing is ever guessed: if the processor cannot determine the
 * union name or its members with certainty, it fails the compilation instead.
 */
internal class UnionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

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

        if (!markerName.endsWith(SPEC_SUFFIX) || markerName.length <= SPEC_SUFFIX.length) {
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

        val unionName = markerName.dropLast(SPEC_SUFFIX.length)
        val qualifiedUnionName = "$packageName.$unionName"
        if (!generated.add(qualifiedUnionName)) {
            logger.error(
                "@Union marker '$markerName' would generate '$qualifiedUnionName', which another " +
                    "marker in the same package already generates. Rename one of them.",
                marker,
            )
            return
        }

        val annotation = marker.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$markerName'.", marker)
            return
        }
        val declaredTypes = annotation.memberTypes() ?: run {
            logger.error(
                "Unable to read the 'types' argument of @Union on '$markerName'.",
                marker,
            )
            return
        }
        if (declaredTypes.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return
        }

        val members = declaredTypes.map { type -> resolveMember(type, markerName, marker) ?: return }

        val collisions = members.groupBy(UnionMember::simpleName).filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            collisions.forEach { (simpleName, clashing) ->
                logger.error(
                    "@Union on '$markerName' has ${clashing.size} member types whose simple name " +
                        "is '$simpleName' (${clashing.joinToString { it.qualifiedName }}), which " +
                        "would generate clashing '$CASE_PREFIX$simpleName' cases. Use a typealias " +
                        "or wrapper type to disambiguate them.",
                    marker,
                )
            }
            return
        }

        // Computed last so the private -> internal warning is only emitted for a marker
        // that actually produces a union.
        val visibility = unionVisibilityOf(marker, markerName) ?: return

        writeUnion(
            marker = marker,
            markerName = markerName,
            packageName = packageName,
            unionName = unionName,
            visibility = visibility,
            members = members,
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

    private fun KSClassDeclaration.unionAnnotation(): KSAnnotation? = annotations.firstOrNull {
        it.shortName.asString() == UNION_ANNOTATION_SIMPLE_NAME &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == UNION_ANNOTATION_NAME
    }

    /** Reads the `vararg types: KClass<*>` argument, which KSP models as a list of [KSType]. */
    private fun KSAnnotation.memberTypes(): List<KSType>? {
        val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return null
        return when (val value = argument.value) {
            is KSType -> listOf(value)
            is List<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
            is Array<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
            else -> null
        }
    }

    private fun resolveMember(
        type: KSType,
        markerName: String,
        marker: KSClassDeclaration,
    ): UnionMember? {
        if (type.isError) {
            logger.error(
                "@Union on '$markerName' references a type that could not be resolved. " +
                    "Check the import.",
                marker,
            )
            return null
        }

        val declaration = type.declaration
        // A `KClass` literal cannot carry type arguments, so a generic member arrives with
        // its parameters unresolved (`List<T>`). Star-projecting keeps the emitted code valid.
        val resolved = if (declaration is KSClassDeclaration && declaration.typeParameters.isNotEmpty()) {
            declaration.asStarProjectedType()
        } else {
            type
        }

        val simpleName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName?.asString() ?: simpleName

        return UnionMember(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            typeName = resolved.toTypeName(),
        )
    }

    private fun writeUnion(
        marker: KSClassDeclaration,
        markerName: String,
        packageName: String,
        unionName: String,
        visibility: KModifier,
        members: List<UnionMember>,
    ) {
        val unionType = ClassName(packageName, unionName)

        val union = TypeSpec.interfaceBuilder(unionType)
            .addModifiers(visibility, KModifier.SEALED)
            .addKdoc(
                "A union of %L.\n\nGenerated from the [%L] marker; `when` over this type is " +
                    "exhaustively checked by the compiler.",
                members.joinToString { "[${it.simpleName}]" },
                markerName,
            )

        val companion = TypeSpec.companionObjectBuilder()

        members.forEach { member ->
            val caseName = CASE_PREFIX + member.simpleName

            union.addType(
                TypeSpec.classBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter(VALUE_NAME, member.typeName)
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder(VALUE_NAME, member.typeName)
                            .initializer(VALUE_NAME)
                            .build(),
                    )
                    .addSuperinterface(unionType)
                    .build(),
            )

            companion.addFunction(
                FunSpec.builder(FACTORY_PREFIX + member.simpleName)
                    .addParameter(VALUE_NAME, member.typeName)
                    .returns(unionType)
                    .addStatement("return %T(%N)", unionType.nestedClass(caseName), VALUE_NAME)
                    .build(),
            )
        }

        val fileSpec = FileSpec.builder(packageName, unionName)
            .addFileComment("Generated by unionKt from @Union on %L. Do not edit.", markerName)
            .addType(union.addType(companion.build()).build())
            .build()

        fileSpec.writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(
                aggregating = false,
                *listOfNotNull(marker.containingFile).toTypedArray(),
            ),
        )
    }

    private data class UnionMember(
        val simpleName: String,
        val qualifiedName: String,
        val typeName: TypeName,
    )

    private companion object {
        val UNION_ANNOTATION_NAME: String = requireNotNull(Union::class.qualifiedName)
        val UNION_ANNOTATION_SIMPLE_NAME: String = requireNotNull(Union::class.simpleName)

        const val TYPES_ARGUMENT = "types"
        const val SPEC_SUFFIX = "Spec"
        const val CASE_PREFIX = "On"
        const val FACTORY_PREFIX = "on"
        const val VALUE_NAME = "value"
    }
}
