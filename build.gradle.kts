import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published as io.github.fcat97.unionkt:<module>:<version>. The release workflow passes
// -PreleaseVersion=<tag>; local builds are snapshots. (Not VERSION_NAME: the publish plugin
// reads that name itself and would try to set the version a second time.)
allprojects {
    group = "io.github.fcat97.unionkt"
    version = providers.gradleProperty("releaseVersion").getOrElse("0.5.0-SNAPSHOT")
}

// Shared Maven Central setup for every module that applies com.vanniktech.maven.publish.
// Each module sets its own `description` and platform (JVM or multiplatform).
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(project.group.toString(), project.name, project.version.toString())

            // Uploaded and validated, then released by hand in the Central Portal.
            publishToMavenCentral()

            // Central requires signatures. Local publishing (no key) stays unsigned.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            pom {
                name.set("unionKt ${project.name}")
                description.set(provider { project.description })
                inceptionYear.set("2026")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("fcat97")
                        name.set("Shahriar Zaman")
                        url.set("https://github.com/fcat97")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
