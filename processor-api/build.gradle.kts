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
    // Both appear in the public signatures, so extensions get them transitively.
    api(libs.ksp.api)
    api(libs.kotlinpoet)
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
                name.set("unionKt processor API")
                description.set("Extension API for the unionKt KSP processor.")
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
