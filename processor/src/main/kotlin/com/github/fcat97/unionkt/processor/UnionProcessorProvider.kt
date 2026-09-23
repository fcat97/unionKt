package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.JvmPlatformInfo
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Entry point discovered by KSP through
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 */
public class UnionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val processor = UnionProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            emitJvmNames = environment.targetsJvm(),
        )
        environment.registerForNewFeaturesIfSupported(processor)
        return processor
    }
}

/**
 * Opts in to KSP's upcoming language-feature handling when the running KSP supports it.
 *
 * `SymbolProcessorEnvironment.registerProcessorForNewFeatures` was only added in KSP
 * 2.3.12. Calling it directly throws `NoSuchMethodError` on every earlier release, so the
 * call is made reflectively and skipped when absent — the processor works identically
 * either way, since it implements no `KSVisitor` and only reads a marker interface's
 * name, visibility and `@Union` arguments.
 */
internal fun SymbolProcessorEnvironment.registerForNewFeaturesIfSupported(processor: SymbolProcessor) {
    val register = try {
        SymbolProcessorEnvironment::class.java.getMethod("getRegisterProcessorForNewFeatures")
    } catch (_: NoSuchMethodException) {
        // KSP < 2.3.12. It logs an informational forward-compatibility notice instead,
        // which is harmless and cannot be suppressed from here.
        return
    }

    @Suppress("UNCHECKED_CAST")
    (register.invoke(this) as? Function1<SymbolProcessor, Unit>)?.invoke(processor)
}

/**
 * Whether the code being generated is compiled for the JVM (alone, or as part of common code
 * shared with a JVM target). `@JvmName` exists only there.
 */
internal fun SymbolProcessorEnvironment.targetsJvm(): Boolean = platforms.any { it is JvmPlatformInfo }
