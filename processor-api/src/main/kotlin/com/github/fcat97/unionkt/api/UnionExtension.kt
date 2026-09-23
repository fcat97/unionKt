package com.github.fcat97.unionkt.api

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

/**
 * A plugin for the unionKt processor.
 *
 * Register an implementation in
 * `META-INF/services/com.github.fcat97.unionkt.api.UnionExtension` and put its artifact on
 * the `ksp` configuration next to the processor. The processor calls it for every union:
 * first [unionAnnotations], whose results are added to the generated union interface, then,
 * after the union file is written, [generate].
 *
 * Report problems through [ExtensionEnvironment.logger]. An exception thrown from either
 * function is reported as a compile error naming the extension.
 */
public interface UnionExtension {
    /** Extra annotations for the generated union interface. Also the place to validate. */
    public fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> = emptyList()

    /** Extra files for this union. */
    public fun generate(union: UnionInfo, env: ExtensionEnvironment) {}
}

/** A fully resolved union: generics, flattening and visibility are already applied. */
public class UnionInfo(
    /** The Spec marker's simple name, e.g. `ResultSpec`. */
    public val markerName: String,
    /** The Spec marker, for attaching diagnostics. */
    public val marker: KSClassDeclaration,
    /** The generated union, e.g. `com.example.Result`. */
    public val unionType: ClassName,
    /** `PUBLIC` or `INTERNAL`. */
    public val visibility: KModifier,
    /** As declared on the union: always `out`, bounds kept. Empty for a non-generic union. */
    public val typeParameters: List<TypeVariableName>,
    /** Every case, in case order. Flattened members are ordinary members here. */
    public val members: List<UnionMemberInfo>,
    /** The files the union was generated from; pass them to `Dependencies`. */
    public val sources: List<KSFile>,
)

/** One case of a union. */
public class UnionMemberInfo(
    /** The case suffix: `Int` for `OnInt`, `L` for `OnL`. */
    public val simpleName: String,
    /** The type the case stores. */
    public val typeName: TypeName,
    /** The case class, e.g. `com.example.Result.OnInt`. */
    public val caseClass: ClassName,
    /** The union type parameter this case stores, or null for a concrete case. */
    public val typeParameterName: String?,
    /** The member's class declaration, or null for a type-parameter case. */
    public val declaration: KSClassDeclaration?,
)

/** What an extension may use while handling one union. */
public class ExtensionEnvironment(
    public val codeGenerator: CodeGenerator,
    public val logger: KSPLogger,
    /** The current round's resolver. */
    public val resolver: Resolver,
)
