import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // No kotlinx.serialization dependency on purpose: this module only *emits* code that
    // uses it, and the consuming project brings the runtime.
    implementation(project(":processor-api"))
    implementation(libs.kotlinpoet.ksp)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // Never hardcoded: both come from the root build script, which reads
            // them from JitPack's environment when running there.
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set("unionKt kotlinx.serialization")
                description.set("unionKt extension that generates untagged kotlinx.serialization JSON serializers for unions.")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
