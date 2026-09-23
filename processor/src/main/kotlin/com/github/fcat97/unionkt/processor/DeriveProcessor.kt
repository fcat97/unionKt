package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName

/**
 * Generates `fold` and case accessors for every `@Derive` sealed class or interface.
 *
 * Every precondition is reported through [KSPLogger.error] and the target is skipped.
 */
internal class DeriveProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val writer = DeriveWriter(codeGenerator)

    /** Guards against two targets producing the same file. */
    private val generated = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(DERIVE_ANNOTATION_NAME).toList()
        // See UnionProcessor: the single-argument form exists in every KSP 2.x release.
        @Suppress("DEPRECATION")
        val (resolvable, deferred) = symbols.partition { it.validate() }

        resolvable.forEach { symbol ->
            if (symbol is KSClassDeclaration) {
                derive(symbol)
            } else {
                // @Derive targets CLASS, so this is unreachable in practice.
                logger.error("@Derive may only be applied to a sealed class or interface.", symbol)
            }
        }
        return deferred
    }

    private fun derive(target: KSClassDeclaration) {
        val name = target.simpleName.asString()

        val sealed = Modifier.SEALED in target.modifiers &&
            (target.classKind == ClassKind.CLASS || target.classKind == ClassKind.INTERFACE)
        if (!sealed) {
            logger.error(
                "@Derive may only be applied to a sealed class or interface, but '$name' is ${describeKind(target)}.",
                target,
            )
            return
        }

        val packageName = target.packageName.asString()
        if (packageName.isBlank()) {
            logger.error(
                "@Derive target '$name' must live in a named package; the helpers are generated into " +
                    "its package and the default package is not addressable.",
                target,
            )
            return
        }

        val visibility = effectiveVisibility(target) ?: run {
            logger.error(
                "@Derive target '$name' is not visible to other files (it, or a class containing it, " +
                    "is private or protected). Make it internal or public.",
                target,
            )
            return
        }

        val targetClass = target.toClassName()
        val fileName = targetClass.simpleNames.joinToString("") + "Derived"
        if (!generated.add("$packageName.$fileName")) {
            logger.error(
                "@Derive on '$name' would generate '$packageName.$fileName.kt', which another @Derive " +
                    "target already generates. Rename one of them.",
                target,
            )
            return
        }

        val subclasses = target.getSealedSubclasses().toList()
        if (subclasses.isEmpty()) {
            logger.error(
                "@Derive on '$name' found no subclasses. A sealed type needs at least one to derive helpers for.",
                target,
            )
            return
        }

        val typeParameterResolver = target.typeParameters.toTypeParameterResolver()
        val typeVariables = target.typeParameters.map { parameter ->
            TypeVariableName(
                parameter.name.asString(),
                parameter.toTypeVariableName(typeParameterResolver).bounds.filter { it != NULLABLE_ANY },
            )
        }

        var valid = true
        val cases = subclasses.mapNotNull { subclass ->
            val caseVisibility = effectiveVisibility(subclass) ?: run {
                logger.error(
                    "@Derive on '$name' cannot reference subclass '${subclass.qualifiedName?.asString()}': it, " +
                        "or a class containing it, is private or protected, so the generated file cannot " +
                        "see it. Make it internal or public.",
                    target,
                )
                valid = false
                return@mapNotNull null
            }
            caseOf(subclass, target, caseVisibility)
        }
        if (!valid) return

        val collisions = cases.zip(subclasses).groupBy { (case, _) -> case.simpleName }.filterValues { it.size > 1 }
        collisions.forEach { (simpleName, clashing) ->
            logger.error(
                "@Derive on '$name' has ${clashing.size} subclasses whose simple name is '$simpleName' " +
                    "(${clashing.joinToString { (_, subclass) -> subclass.qualifiedName?.asString() ?: simpleName }}), " +
                    "which would generate clashing '$FACTORY_PREFIX$simpleName' handlers. Rename one of them.",
                target,
            )
        }
        if (collisions.isNotEmpty()) return

        writer.write(
            DeriveModel(
                target = targetClass,
                fileName = fileName,
                typeVariables = typeVariables,
                receiver = if (typeVariables.isEmpty()) targetClass else targetClass.parameterizedBy(typeVariables.map { TypeVariableName(it.name) }),
                visibility = visibility,
                cases = cases,
                sources = (listOf(target) + subclasses).mapNotNull { it.containingFile }.distinct(),
            ),
        )
    }

    /**
     * A case's type in terms of the target's type parameters (spec §3.1): each of the case's
     * own type parameters maps to the target parameter whose supertype position it fills
     * exactly, and to `*` otherwise.
     */
    private fun caseOf(subclass: KSClassDeclaration, target: KSClassDeclaration, visibility: KModifier): DeriveCase {
        val className = subclass.toClassName()
        val isObject = subclass.classKind == ClassKind.OBJECT
        if (subclass.typeParameters.isEmpty()) {
            return DeriveCase(subclass.simpleName.asString(), className, className, isObject, visibility)
        }

        val targetName = target.qualifiedName?.asString()
        val supertypeArguments = subclass.superTypes
            .map { it.resolve() }
            .firstOrNull { it.declaration.qualifiedName?.asString() == targetName }
            ?.arguments
            .orEmpty()
        val targetParameters = target.typeParameters.map { it.name.asString() }

        val arguments: List<TypeName> = subclass.typeParameters.map { own ->
            val position = supertypeArguments.indexOfFirst { argument ->
                val type = argument.type?.resolve()
                argument.variance != Variance.STAR &&
                    type != null &&
                    !type.isMarkedNullable &&
                    (type.declaration as? KSTypeParameter)?.name?.asString() == own.name.asString()
            }
            if (position in targetParameters.indices) TypeVariableName(targetParameters[position]) else STAR
        }

        val typeName = className.parameterizedBy(arguments)
        val checkType = if (arguments.none { it == STAR }) className else className.parameterizedBy(arguments.map { STAR })
        return DeriveCase(subclass.simpleName.asString(), checkType, typeName, isObject, visibility)
    }

    /** PUBLIC or INTERNAL as seen from another file, or null when the declaration is not visible there. */
    private fun effectiveVisibility(declaration: KSDeclaration): KModifier? {
        var result = KModifier.PUBLIC
        var current: KSDeclaration? = declaration
        while (current != null) {
            when (current.getVisibility()) {
                Visibility.PUBLIC -> Unit
                Visibility.INTERNAL -> result = KModifier.INTERNAL
                else -> return null
            }
            current = current.parentDeclaration
        }
        return result
    }

    private fun describeKind(declaration: KSClassDeclaration): String = when (declaration.classKind) {
        ClassKind.CLASS -> "a class that is not sealed"
        ClassKind.INTERFACE -> "an interface that is not sealed"
        ClassKind.OBJECT -> "an object"
        ClassKind.ENUM_CLASS -> "an enum class"
        ClassKind.ENUM_ENTRY -> "an enum entry"
        ClassKind.ANNOTATION_CLASS -> "an annotation class"
    }

    private companion object {
        val NULLABLE_ANY: TypeName = ANY.copy(nullable = true)
    }
}
