package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

/** One direct subclass of a `@Derive` target. */
internal data class DeriveCase(
    /** Handler and accessor suffix: `onSuccess`, `isSuccess`, `successOrNull`. */
    val simpleName: String,
    /** What `is` checks against: the bare class, or a star projection when Kotlin cannot infer. */
    val checkType: TypeName,
    /** The case's type in helper signatures, in terms of the target's type parameters. */
    val typeName: TypeName,
    val isObject: Boolean,
    /** Effective visibility: PUBLIC or INTERNAL. */
    val visibility: KModifier,
) {
    /** Some type arguments are mapped and some are `*`: the smart cast needs help. */
    val needsCast: Boolean
        get() = typeName is ParameterizedTypeName &&
            typeName.typeArguments.any { it == STAR } &&
            typeName.typeArguments.any { it != STAR }
}

/** Everything [DeriveWriter] needs. */
internal data class DeriveModel(
    val target: ClassName,
    /** `UiStateDerived`, `ScreenStateDerived`. */
    val fileName: String,
    /** The target's type parameters as function type parameters: no variance, bounds kept. */
    val typeVariables: List<TypeVariableName>,
    /** `UiState`, or `Result<T>`. */
    val receiver: TypeName,
    /** The target's effective visibility: PUBLIC or INTERNAL. */
    val visibility: KModifier,
    val cases: List<DeriveCase>,
    /** The target's file and every case's file. */
    val sources: List<KSFile>,
)
