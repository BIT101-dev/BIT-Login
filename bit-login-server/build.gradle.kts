plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":bit-login"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.cors)
    implementation(libs.logback.classic)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.testkit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("cn.bit101.bitlogin.server.ApplicationKt")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf")
    }
    systemProperty("BASE_URL", "https://test.example")
}

tasks.register<Test>("perfTest") {
    useJUnitPlatform {
        includeTags("perf")
    }
    systemProperty("BASE_URL", "https://test.example")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    testLogging {
        showStandardStreams = true
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "bit-login-server"
}
