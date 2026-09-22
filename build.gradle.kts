plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

// ---------------------------------------------------------------------------
// Coordinates
// ---------------------------------------------------------------------------
// JitPack does not pass -Pgroup/-Pversion. It exports environment variables into
// the build container instead:
//
//     GROUP    = com.github.<user>      (e.g. com.github.fcat97)
//     ARTIFACT = <repository name>      (e.g. unionKt)
//     VERSION  = <git tag being built>  (e.g. 0.1.0)
//
// For a MULTI-MODULE repository the published group is "<user>.<repo>" and the
// artifactId is the module name, which is how consumers end up writing
//
//     com.github.fcat97.unionKt:annotations:<tag>
//
// so GROUP and ARTIFACT are joined here. Outside JitPack (local development, CI)
// the fallbacks below keep the build publishable to mavenLocal.
val jitpackGroup: String? = System.getenv("GROUP")?.takeIf { it.isNotBlank() }
val jitpackArtifact: String? = System.getenv("ARTIFACT")?.takeIf { it.isNotBlank() }
val jitpackVersion: String? = System.getenv("VERSION")?.takeIf { it.isNotBlank() }

allprojects {
    group = when {
        jitpackGroup != null && jitpackArtifact != null -> "$jitpackGroup.$jitpackArtifact"
        jitpackGroup != null -> jitpackGroup
        else -> "com.github.fcat97.unionKt"
    }
    version = jitpackVersion ?: "0.1.0-SNAPSHOT"
}
