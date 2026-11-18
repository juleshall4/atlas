plugins { id("com.android.library") }

android {
    namespace = "com.atlas.keyboard"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        consumerProguardFiles("proguard-rules.pro")
    }
    sourceSets.getByName("main") {
        assets.srcDir("assets")
        jniLibs.srcDir("jniLibs")
    }
}

dependencies {
    // Ink compiles the Kotlin sources in the app module.
    api("org.apache.commons:commons-compress:1.27.1")
}
