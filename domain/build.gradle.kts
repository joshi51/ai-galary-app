plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.paging.common)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
