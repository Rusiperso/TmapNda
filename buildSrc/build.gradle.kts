plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.0.1")
    implementation("org.ow2.asm:asm:9.6")
}

gradlePlugin {
    plugins {
        create("audioFocusInterceptor") {
            id = "com.tmap.nda.audiofocus"
            implementationClass = "com.tmap.nda.plugin.AudioFocusInterceptorPlugin"
        }
    }
}
