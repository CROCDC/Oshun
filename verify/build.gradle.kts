plugins {
    kotlin("jvm") version "2.0.21"
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    // Needed by core/ (BridgeState uses kotlinx StateFlow).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // net/ parses the AIS feed with org.json, which Android ships in its framework and a
    // plain JVM does not. Same API, so the same source compiles here and in the app.
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

// Compile the SAME pure sources the Android app ships, so coverage reflects real code.
// Android-dependent packages (service/, location/, MainActivity) are intentionally
// excluded — they are lifecycle/UI glue verified by the field test, not units.
sourceSets {
    main {
        kotlin.setSrcDirs(
            listOf(
                "../app/src/main/java/com/oshun/gpsbridge/model",
                "../app/src/main/java/com/oshun/gpsbridge/nmea",
                "../app/src/main/java/com/oshun/gpsbridge/net",
                "../app/src/main/java/com/oshun/gpsbridge/core",
            )
        )
    }
    test {
        kotlin.setSrcDirs(
            listOf(
                "../app/src/test/java/com/oshun/gpsbridge/model",
                "../app/src/test/java/com/oshun/gpsbridge/nmea",
                "../app/src/test/java/com/oshun/gpsbridge/net",
                "../app/src/test/java/com/oshun/gpsbridge/core",
            )
        )
    }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// `gradle check` runs tests + the 90% coverage gate.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
