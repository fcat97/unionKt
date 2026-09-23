package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toTypeName

/** Works out the cases of a union from its marker's `@Union` arguments. */
internal class MemberResolver(private val logger: KSPLogger) {

    /**
     * The union's cases: the annotation's types in order, then the marker's type
     * parameters in declaration order. Null after reporting why there are none.
     */
    fun resolve(
        marker: KSClassDeclaration,
        markerName: String,
        typeParameters: List<UnionTypeParameter>,
    ): List<UnionMember>? {
        val annotation = marker.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$markerName'.", marker)
            return null
        }
        val declaredTypes = annotation.memberTypes() ?: run {
            logger.error(
                "Unable to read the 'types' argument of @Union on '$markerName'.",
                marker,
            )
            return null
        }
        if (declaredTypes.isEmpty() && typeParameters.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return null
        }

        val concrete = declaredTypes.map { type -> resolveMember(type, markerName, marker) ?: return null }
        val generic = typeParameters.map { parameter ->
            UnionMember(
                simpleName = parameter.name,
                qualifiedName = parameter.name,
                typeName = TypeVariableName(parameter.name),
                typeParameter = parameter,
            )
        }
        val members = concrete + generic
        if (!reportCollisions(members, markerName, marker)) return null
        return members
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
                    "is '$simpleName' (${clashing.joinToString { it.qualifiedName }}), which " +
                    "would generate clashing '$CASE_PREFIX$simpleName' cases. Use a typealias " +
                    "or wrapper type to disambiguate them.",
                marker,
            )
        }
        return collisions.isEmpty()
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
