package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

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
    /** The chain of nested markers this member was flattened in through, e.g. `ShapeSpec → PolygonSpec`. */
    val via: String? = null,
    /** The member's class declaration; null for a type-parameter case. Exposed to extensions. */
    val declaration: KSClassDeclaration? = null,
)

/** A type parameter of the marker, and therefore of the union. Always emitted as `out`. */
internal data class UnionTypeParameter(
    val name: String,
    /** Declared upper bounds, with the implicit `Any?` removed. */
    val bounds: List<TypeName>,
)

/** A union whose cases were inlined into another; each one gets a `toX()` conversion. */
internal data class FlattenedUnion(
    val unionType: ClassName,
    val visibility: KModifier,
    /** The flattened union's own cases, in its order. */
    val members: List<UnionMember>,
)

/** Everything [UnionWriter] needs to emit one union. */
internal data class UnionModel(
    val markerName: String,
    val unionType: ClassName,
    val visibility: KModifier,
    val members: List<UnionMember>,
    val typeParameters: List<UnionTypeParameter> = emptyList(),
    val flattened: List<FlattenedUnion> = emptyList(),
)

/** As declared on the union and on its case class: always `out`, bounds kept. */
internal fun UnionTypeParameter.declaredVariable(): TypeVariableName =
    TypeVariableName(name, bounds, KModifier.OUT)
