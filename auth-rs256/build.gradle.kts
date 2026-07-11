plugins {
    id("sdui.jvm.library")
    alias(libs.plugins.kotlinSerialization)
    id("sdui.publish")
}

sduiPublish {
    description.set("RS256 JWT signing + JWKS publication for sdui-kmp servers.")
}

dependencies {
    // Generic, reusable JVM library — deliberately has zero project dependencies. Only the
    // bare minimum third-party libraries Auth0's java-jwt + Ktor server APIs need.
    api(libs.auth0.java.jwt)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
