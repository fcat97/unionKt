package com.github.fcat97.unionkt.processor

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
        )
        // Opt in to KSP's upcoming language-feature handling. Safe here because the
        // processor implements no KSVisitor: it only reads a marker interface's name,
        // visibility and @Union arguments. Without this, KSP logs a forward-compatibility
        // notice on every build. Requires KSP 2.3.0 or newer.
        environment.registerProcessorForNewFeatures(processor)
        return processor
    }
}
