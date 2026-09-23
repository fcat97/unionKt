package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Entry point for `@Derive`, discovered next to [UnionProcessorProvider]. */
public class DeriveProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val processor = DeriveProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
        environment.registerForNewFeaturesIfSupported(processor)
        return processor
    }
}
