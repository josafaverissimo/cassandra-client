plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass = "cassandraclient.server.MainKt"
}

dependencies {
    implementation(project(":backend"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
}

// The server hands out the Wasm build of the UI from its classpath.
tasks.processResources {
    from(project(":app").tasks.named("wasmJsBrowserDistribution")) {
        into("web")
    }
}
