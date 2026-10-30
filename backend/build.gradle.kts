plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(libs.coroutines.core)
    implementation(libs.cassandra.driver)
    implementation(libs.slf4j.simple)
}
