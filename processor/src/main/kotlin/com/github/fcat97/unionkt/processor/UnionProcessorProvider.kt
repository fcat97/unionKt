package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Entry point discovered by KSP through
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`.
 */
public class UnionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        UnionProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
}
