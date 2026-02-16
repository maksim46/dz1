plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}


fun readApiKey(): String {
    fun fromFile(f: java.io.File): String? =
        f.takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("deepseek.api.key=") }
            ?.substringAfter("deepseek.api.key=", "")?.trim()?.takeIf { it.isNotEmpty() }
    return fromFile(rootProject.file("local.properties")) ?: ""
}

val deepseekApiKey = readApiKey().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.deepseek"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.deepseek"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    implementation(libs.compose.ui)
    implementation(libs.splash)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.runtime.livedata)

    implementation(libs.datastore)

    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    kapt(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.serialization)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(kotlin("test"))
}

afterEvaluate {
    tasks.register<JavaExec>("runConsole") {
        group = "application"
        description = "Запуск запросов к DeepSeek API (вывод в консоль)"
        mainClass.set("com.example.deepseek.console.MainKt")
        jvmArgs("-Dfile.encoding=UTF-8")
        val compileKotlin = tasks.named("compileDebugKotlin").get()
        dependsOn(compileKotlin)
        val kotlinClasses = compileKotlin.outputs.files
        val runtimeCp = configurations.getByName("debugRuntimeClasspath")
        classpath(kotlinClasses + runtimeCp)
        if (project.hasProperty("consoleArgs")) {
            args((project.property("consoleArgs") as String).split(" "))
        }
    }
}
