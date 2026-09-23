package com.github.fcat97.unionkt.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/** One case of a union. */
internal data class UnionMember(
    /** Case suffix: `On<simpleName>`, `on<simpleName>`. The type parameter's name for a generic case. */
    val simpleName: String,
    /** Fully-qualified name, used in diagnostics. Equal to [simpleName] for a type parameter. */
    val qualifiedName: String,
    /** The type the case stores. For a type parameter, the bare `TypeVariableName`. */
    val typeName: TypeName,
    /** Non-null when the case is one of the union's own type parameters. */
    val typeParameter: UnionTypeParameter? = null,
)

/** A type parameter of the marker, and therefore of the union. Always emitted as `out`. */
internal data class UnionTypeParameter(
    val name: String,
    /** Declared upper bounds, with the implicit `Any?` removed. */
    val bounds: List<TypeName>,
)

/** Everything [UnionWriter] needs to emit one union. */
internal data class UnionModel(
    val markerName: String,
    val unionType: ClassName,
    val visibility: KModifier,
    val members: List<UnionMember>,
    val typeParameters: List<UnionTypeParameter> = emptyList(),
)
