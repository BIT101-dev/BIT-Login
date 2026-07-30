import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

kotlin {
    android {
        namespace = "cn.bit101.bitlogin"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.client.encoding)
            }
        }

        // Shared source set compiled for BOTH the Android and JVM targets.
        // Holds expect/actual bodies that rely on JVM-family APIs available on
        // both platforms: javax.crypto, java.security, java.time, org.jsoup,
        // java.net, java.util. Android (minSdk 26) and JVM (17) both provide them.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jsoup)
            }
        }
        androidMain {
            dependsOn(jvmShared)
        }
        jvmMain {
            dependsOn(jvmShared)
        }

        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.register<JavaExec>("classroomManualTest") {
    group = "verification"
    description = "交互式测试空闲教室接口可用性（运行时输入统一身份认证凭据）"
    val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath = jvmTestCompilation.output.allOutputs +
            (jvmTestCompilation.runtimeDependencyFiles ?: files())
    mainClass.set("cn.bit101.bitlogin.manual.ClassroomManualTestKt")
    standardInput = System.`in`
}
