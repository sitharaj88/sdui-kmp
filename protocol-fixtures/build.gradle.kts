plugins {
    id("sdui.kmp.module")
    id("sdui.publish")
}

sduiPublish {
    description.set(
        "sdui-kmp protocol fixtures: canonical JSON corpus and shared Kotlin values " +
            "exercising every shape in :protocol, used by contract tests on both sides.",
    )
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
        }
        commonTest.dependencies {
            implementation(project(":protocol"))
        }
    }
}
