plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.8")
}

kotlin {
    jvmToolchain(21)
}
