plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Compile the SAME sources the Android app ships, so this test verifies the real code.
sourceSets {
    main {
        kotlin.setSrcDirs(
            listOf(
                "../app/src/main/java/com/oshun/gpsbridge/model",
                "../app/src/main/java/com/oshun/gpsbridge/nmea",
            )
        )
    }
    test {
        kotlin.setSrcDirs(listOf("../app/src/test/java/com/oshun/gpsbridge/nmea"))
    }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
