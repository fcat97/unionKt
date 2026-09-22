import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Compiles Kotlin sources in-memory with the processor attached so
// the error paths can be asserted on, which a normal build cannot do: a failing
// processor fails the build.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // kotlin-compile-testing drives the compiler through its plugin API, which is
        // experimental by definition. Opting in once here beats annotating every call.
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(project(":annotations"))
    testImplementation(project(":processor"))
    testImplementation(libs.kotlin.compile.testing.ksp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
