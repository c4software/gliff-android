import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":proto"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sshj)
            implementation(libs.bouncycastle)
        }
        jvmTest.dependencies {
            implementation(libs.coroutines.test)
            runtimeOnly(libs.slf4j.nop)
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The headless test client: ./gradlew :transport:probe --args="user@host --headless 1280x720"
val probeLogging by configurations.creating

dependencies {
    probeLogging(libs.slf4j.nop)
}

tasks.register<JavaExec>("probe") {
    group = "application"
    description = "Connect to a gliff server and dump its H.264 stream to a file."
    val main = kotlin.jvm().compilations["main"]
    mainClass = "io.github.c4software.gliff.transport.ProbeKt"
    classpath = main.output.allOutputs + main.runtimeDependencyFiles + probeLogging
    workingDir = rootDir
    standardInput = System.`in`
}

// Writes the classpath scripts/fake-server.sh runs the stand-in server with.
tasks.register("toolsClasspath") {
    val main = kotlin.jvm().compilations["main"]
    val files = main.output.allOutputs + main.runtimeDependencyFiles + probeLogging
    val out = layout.buildDirectory.file("tools/classpath.txt")
    dependsOn(main.compileTaskProvider)
    inputs.files(files)
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(files.asPath)
    }
}
