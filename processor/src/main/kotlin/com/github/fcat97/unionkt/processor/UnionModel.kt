package com.github.fcat97.unionkt.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/** One case of a union. */
internal data class UnionMember(
    /** Case suffix: `On<simpleName>`, `on<simpleName>`. */
    val simpleName: String,
    /** Fully-qualified name, used in diagnostics. */
    val qualifiedName: String,
    /** The type the case stores. */
    val typeName: TypeName,
)

/** Everything [UnionWriter] needs to emit one union. */
internal data class UnionModel(
    val markerName: String,
    val unionType: ClassName,
    val visibility: KModifier,
    val members: List<UnionMember>,
)
