package kmptests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** `@JvmName` must reach JVM output only (spec §2.1). Reads this module's KSP output. */
class GeneratedCodeTest {

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(dir)
        assertTrue(root.isDirectory, "no KSP output at $root")
        return root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    @Test
    fun jsAndNativeOutputHasNoJvmName() {
        val offenders = (kotlinFiles("build/generated/ksp/js/jsMain/kotlin") +
            kotlinFiles("build/generated/ksp/linuxX64/linuxX64Main/kotlin"))
            .filter { "JvmName" in it.readText() }
        assertTrue(offenders.isEmpty(), "JvmName found in $offenders")
    }

    @Test
    fun jvmOutputKeepsJvmName() {
        val result = kotlinFiles("build/generated/ksp/jvm/jvmMain/kotlin").single { it.name == "Result.kt" }
        assertTrue("@file:JvmName(\"ResultUnionKt\")" in result.readText(), result.readText())
    }
}
