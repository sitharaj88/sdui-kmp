plugins {
    id("sdui.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp live transport: WebSocketLiveSource for streaming live state and " +
            "incremental tree updates from the server to connected clients.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":runtime"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            // Ktor server WebSockets powers WebSocketLivePublisher + installLiveScreensRoute,
            // the JVM-only publisher half of the live transport. Only the JVM target ships
            // a Ktor server, so this lives in jvmMain and the rest of the source set stays
            // client-only.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.websockets)
            // ktor-server-auth enables the optional authenticate(...) wrapper around
            // installLiveScreensRoute so studio + sample-server can gate /live/screens/{id}
            // behind their JWT provider. Default-off would keep this out of jvmMain, but the
            // wrapper is enabled by default — keeping the dep here means hosts do not need
            // to know it is required.
            implementation(libs.ktor.server.auth)
            // Postgres JDBC driver — backs PostgresLivePublisher's LISTEN/NOTIFY listener
            // connection. Hosts that run on a real Postgres cluster wire it via DataSource;
            // in-process tests fall back to InProcessLiveBus. The driver lives in jvmMain
            // (not test) so library consumers using this transport in production get it
            // transitively.
            implementation(libs.postgresql)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.server.auth.jwt)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // PostgresLivePublisher tests use an in-process fake DataSource that fronts a
            // fake PGConnection (LISTEN/NOTIFY are Postgres extensions; H2 even in PG-mode
            // does not implement them). The fake routes NOTIFY statements through a shared
            // queue that every listener connection drains via getNotifications — close
            // enough to the real driver semantics that the round-trip, reconnect, and
            // payload-size paths are exercised without Docker.
        }
    }
}
