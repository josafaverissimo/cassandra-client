import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvm()
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(project(":backend"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
        }
    }
}

compose.resources {
    packageOfResClass = "cassandraclient.ui.res"
}

compose.desktop {
    application {
        mainClass = "cassandraclient.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "cassandra-client"
            packageVersion = "1.0.0"
            modules("java.naming", "java.sql", "java.management", "jdk.unsupported", "java.security.jgss")
        }
    }
}
