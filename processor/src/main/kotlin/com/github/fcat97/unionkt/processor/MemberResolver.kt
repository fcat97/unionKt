package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toTypeName

/** The resolved cases of a union, plus everything flattening pulled in. */
internal data class Resolution(
    val members: List<UnionMember>,
    /** Every union inlined into this one, directly or transitively. */
    val flattened: List<FlattenedUnion>,
    /** Source files the generated union depends on: its marker's and every local flattened marker's. */
    val sources: List<KSFile>,
)

/**
 * Works out the cases of a union from its marker's `@Union` arguments.
 *
 * A member whose declaration is itself a `@Union` marker is flattened: its cases are
 * inlined, recursively. Members are then merged by exact type; different types that
 * share a simple name are an error, because they would generate clashing cases.
 */
internal class MemberResolver(private val logger: KSPLogger) {

    /**
     * The union's cases: the annotation's types in order with nested markers expanded in
     * place, then the marker's type parameters. Null after reporting every problem.
     */
    fun resolve(
        marker: KSClassDeclaration,
        markerName: String,
        typeParameters: List<UnionTypeParameter>,
    ): Resolution? {
        val declaredTypes = declaredTypesOf(marker, markerName, reportOn = marker) ?: return null
        if (declaredTypes.isEmpty() && typeParameters.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return null
        }

        val flattened = linkedMapOf<ClassName, FlattenedUnion>()
        val sources = linkedSetOf<KSFile>()
        marker.containingFile?.let(sources::add)

        val expanded = expand(
            root = marker,
            rootName = markerName,
            declaredTypes = declaredTypes,
            path = listOf(marker),
            flattened = flattened,
            sources = sources,
        ) ?: return null

        reportDirectDuplicates(expanded, markerName, marker)

        val generic = typeParameters.map { parameter ->
            UnionMember(
                simpleName = parameter.name,
                qualifiedName = parameter.name,
                typeName = TypeVariableName(parameter.name),
                typeParameter = parameter,
            )
        }
        val members = (expanded + generic).distinctBy(UnionMember::typeName)
        if (!reportCollisions(members, markerName, marker)) return null

        return Resolution(members, flattened.values.toList(), sources.toList())
    }

    /**
     * The members declared by one marker, nested markers expanded in place, duplicates
     * kept (the caller merges them). [path] runs from the root marker to the marker whose
     * [declaredTypes] these are; it drives cycle detection and the `via` in diagnostics.
     */
    private fun expand(
        root: KSClassDeclaration,
        rootName: String,
        declaredTypes: List<KSType>,
        path: List<KSClassDeclaration>,
        flattened: MutableMap<ClassName, FlattenedUnion>,
        sources: MutableSet<KSFile>,
    ): List<UnionMember>? {
        val via = path.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" → ") { it.simpleName.asString() }
        val members = mutableListOf<UnionMember>()

        for (type in declaredTypes) {
            if (type.isError) {
                logger.error(
                    "@Union on '$rootName' references a type that could not be resolved. " +
                        "Check the import.",
                    root,
                )
                return null
            }

            val nested = (type.declaration as? KSClassDeclaration)?.takeIf { it.unionAnnotation() != null }
            if (nested == null) {
                members += concreteMember(type, via)
            } else {
                members += flatten(root, rootName, nested, path, flattened, sources) ?: return null
            }
        }
        return members
    }

    /** Inlines a nested marker's members and records it for a `toX()` conversion. */
    private fun flatten(
        root: KSClassDeclaration,
        rootName: String,
        nested: KSClassDeclaration,
        path: List<KSClassDeclaration>,
        flattened: MutableMap<ClassName, FlattenedUnion>,
        sources: MutableSet<KSFile>,
    ): List<UnionMember>? {
        val nestedName = nested.simpleName.asString()

        if (path.any { it.qualifiedName?.asString() == nested.qualifiedName?.asString() }) {
            val cycle = (path + nested).joinToString(" → ") { it.simpleName.asString() }
            logger.error(
                "@Union on '$rootName' has a flattening cycle: $cycle. Remove one of the references.",
                root,
            )
            return null
        }

        if (nested.typeParameters.isNotEmpty()) {
            logger.error(
                "@Union on '$rootName' cannot flatten generic union '$nestedName': a class " +
                    "literal cannot say which type arguments are meant. List the concrete member " +
                    "types instead, or reference the generated union to keep it as a single case.",
                root,
            )
            return null
        }

        val nestedUnionName = unionNameOf(nestedName) ?: run {
            logger.error(
                "@Union on '$rootName' cannot flatten '$nestedName': it is annotated with @Union " +
                    "but its name does not end in '$SPEC_SUFFIX', so its union's name is unknown.",
                root,
            )
            return null
        }

        val nestedTypes = declaredTypesOf(nested, nestedName, reportOn = root) ?: return null
        val members = expand(root, rootName, nestedTypes, path + nested, flattened, sources) ?: return null

        val unionType = ClassName(nested.packageName.asString(), nestedUnionName)
        flattened.getOrPut(unionType) {
            FlattenedUnion(unionType, visibilityOf(nested), members.distinctBy(UnionMember::typeName))
        }
        nested.containingFile?.let(sources::add)
        return members
    }

    private fun declaredTypesOf(
        spec: KSClassDeclaration,
        specName: String,
        reportOn: KSClassDeclaration,
    ): List<KSType>? {
        val annotation = spec.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$specName'.", reportOn)
            return null
        }
        return annotation.memberTypes() ?: run {
            logger.error("Unable to read the 'types' argument of @Union on '$specName'.", reportOn)
            null
        }
    }

    /** The visibility of a marker's generated union: `private` markers yield `internal` unions. */
    private fun visibilityOf(spec: KSClassDeclaration): KModifier =
        if (spec.getVisibility() == Visibility.PUBLIC) KModifier.PUBLIC else KModifier.INTERNAL

    /** Warns when the root marker itself lists one type more than once (likely a typo). */
    private fun reportDirectDuplicates(
        expanded: List<UnionMember>,
        markerName: String,
        marker: KSClassDeclaration,
    ) {
        expanded.filter { it.via == null }
            .groupBy(UnionMember::typeName)
            .filterValues { it.size > 1 }
            .keys
            .forEach { typeName ->
                logger.warn(
                    "@Union on '$markerName' lists '$typeName' more than once; the duplicates are merged.",
                    marker,
                )
            }
    }

    /** Reports every simple-name clash; true when there were none. */
    private fun reportCollisions(
        members: List<UnionMember>,
        markerName: String,
        marker: KSClassDeclaration,
    ): Boolean {
        val collisions = members.groupBy(UnionMember::simpleName).filterValues { it.size > 1 }
        collisions.forEach { (simpleName, clashing) ->
            logger.error(
                "@Union on '$markerName' has ${clashing.size} member types whose simple name " +
                    "is '$simpleName' (${clashing.joinToString { it.describe() }}), which " +
                    "would generate clashing '$CASE_PREFIX$simpleName' cases. Use a typealias " +
                    "or wrapper type to disambiguate them.",
                marker,
            )
        }
        return collisions.isEmpty()
    }

    private fun UnionMember.describe(): String = if (via == null) qualifiedName else "$qualifiedName via $via"

    private fun concreteMember(type: KSType, via: String?): UnionMember {
        val declaration = type.declaration
        // A `KClass` literal cannot carry type arguments, so a generic member arrives with
        // its parameters unresolved (`List<T>`). Star-projecting keeps the emitted code valid.
        val resolved = if (declaration is KSClassDeclaration && declaration.typeParameters.isNotEmpty()) {
            declaration.asStarProjectedType()
        } else {
            type
        }

        val simpleName = declaration.simpleName.asString()
        return UnionMember(
            simpleName = simpleName,
            qualifiedName = declaration.qualifiedName?.asString() ?: simpleName,
            typeName = resolved.toTypeName(),
            via = via,
        )
    }
}

internal fun KSClassDeclaration.unionAnnotation(): KSAnnotation? = annotations.firstOrNull {
    it.shortName.asString() == UNION_ANNOTATION_SIMPLE_NAME &&
        it.annotationType.resolve().declaration.qualifiedName?.asString() == UNION_ANNOTATION_NAME
}

/** Reads the `vararg types: KClass<*>` argument, which KSP models as a list of [KSType]. */
internal fun KSAnnotation.memberTypes(): List<KSType>? {
    val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return null
    return when (val value = argument.value) {
        is KSType -> listOf(value)
        is List<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        is Array<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        else -> null
    }
}
