import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Tests for :serialization-kotlinx, kept out of :processor-tests because
// the extension is discovered from the classpath: there it would activate in every test.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Overridable so the suite can be run against the oldest supported runtime:
//   ./gradlew :serialization-kotlinx-tests:test -PkotlinxSerializationVersion=1.6.3
val kotlinxSerializationVersion: String = providers.gradleProperty("kotlinxSerializationVersion")
    .getOrElse(libs.versions.kotlinxSerialization.get())

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(testFixtures(project(":processor-tests")))
    testImplementation(project(":serialization-kotlinx"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation(libs.kotlin.serialization.plugin.embeddable)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    inputs.property("kotlinxSerializationVersion", kotlinxSerializationVersion)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
