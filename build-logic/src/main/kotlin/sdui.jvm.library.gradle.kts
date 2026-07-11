plugins {
    id("org.jetbrains.kotlin.jvm")
    id("sdui.detekt")
    id("sdui.coverage")
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}
