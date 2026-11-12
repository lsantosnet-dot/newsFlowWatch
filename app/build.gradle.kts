import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ID do projeto Firebase usado como padrão. Não fica no código: vem de
// `-PfirebaseProjectId=...` ou da variável de ambiente FIREBASE_PROJECT_ID
// (no GitHub Actions, uma Variable do repositório). Se vier vazio, o app
// pede o ID na tela de Configuração do relógio.
val firebaseProjectId: String =
    (project.findProperty("firebaseProjectId") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("FIREBASE_PROJECT_ID").orEmpty()

android {
    namespace = "com.lsantosnet.newsflow.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lsantosnet.newsflow.watch"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_PROJECT_ID", "\"${firebaseProjectId.trim()}\"")
    }

    signingConfigs {
        // Chave fixa só para instalação manual (sideload) de um app pessoal:
        // garante que todo APK gerado, local ou no CI, tenha a mesma assinatura
        // e possa ser instalado por cima do anterior. Não serve para a Play Store.
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "newsflow"
            keyAlias = "sideload"
            keyPassword = "newsflow"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.wear.compose:compose-material3:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.compose:compose-navigation:1.5.6")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Indicador de "atividade em andamento" no mostrador durante a leitura em voz alta.
    implementation("androidx.wear:wear-ongoing:1.1.0")
    // Teclado/ditado do sistema para digitar o ID do projeto.
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // No aparelho o org.json vem do Android; nos testes de JVM precisa da lib.
    testImplementation("org.json:json:20240303")
}
