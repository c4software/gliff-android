import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.play.publisher)
}

// The version is the git tag, not a number kept by hand. `git describe` gives
// `v1.2.3` on a tagged commit, `v1.2.3-4-gabc1234` four commits later, and a
// bare hash while there is no tag at all: only the first is a release, and
// the name of the others says so. RELEASE_VERSION comes first: the release
// workflow knows its tag without fetching the history.
val describedVersion: String =
    providers.environmentVariable("RELEASE_VERSION").orNull?.takeIf(String::isNotBlank)
        ?: providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            isIgnoreExitValue = true
        }.let { run ->
            run.standardOutput.asText.get().trim().takeIf { run.result.get().exitValue == 0 && it.isNotEmpty() }
        }
        ?: "0.0.0-unknown"

// major x 1,000,000 + minor x 1,000 + patch: grows with the version, and never
// 0, which Android refuses. A build that is no release keeps the code of the
// tag it descends from, or 1.
val derivedVersionCode: Int =
    Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(describedVersion)?.destructured
        ?.let { (major, minor, patch) -> major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt() }
        ?.coerceAtLeast(1)
        ?: 1

android {
    namespace = "io.github.c4software.gliff.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.c4software.gliff"
        minSdk = 29
        targetSdk = 36
        versionCode = derivedVersionCode
        versionName = describedVersion.removePrefix("v")
    }

    // Production signing, from outside the repository: the environment
    // (RELEASE_KEYSTORE, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS,
    // RELEASE_KEY_PASSWORD), or else the properties file that the
    // `release.signing` line of local.properties points to (storeFile,
    // storePassword, keyAlias, keyPassword). With neither, a release build
    // comes out unsigned rather than failing: anyone can build the project.
    val signingFile = rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("release.signing") }
        ?.let(::File)?.takeIf(File::exists)
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    fun signing(variable: String, property: String): String? =
        providers.environmentVariable(variable).orNull ?: signingFile?.getProperty(property)
    val releaseKeystore = signing("RELEASE_KEYSTORE", "storeFile")?.let(::File)?.takeIf(File::exists)

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signing("RELEASE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = signing("RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Compressed dex: a third of the size to push over a slow adb link.
        dex {
            useLegacyPackaging = true
        }
        resources {
            // Tables of post-quantum schemes ssh does not use.
            excludes += "org/bouncycastle/pqc/**"
            // The BouncyCastle jars each ship these.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

// Internal testing on the Play Store, for the release workflow. The plugin
// only wakes up with a service account key (PLAY_SERVICE_ACCOUNT_JSON, a
// path): without it, as on any developer's machine, the publish tasks do
// nothing. Release notes are read from src/main/play/release-notes, links to
// store/release-notes.
play {
    val credentials = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON").orNull?.let(::File)?.takeIf(File::exists)
    enabled.set(credentials != null)
    if (credentials != null) serviceAccountCredentials.set(credentials)
    track.set("internal")
    releaseStatus.set(ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    runtimeOnly(libs.slf4j.nop)
}
