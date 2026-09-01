plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.paging.common)
}
