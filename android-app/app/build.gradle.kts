plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Il plugin si applica solo se il file di Firebase c'è. Le notifiche push sono
// opzionali per dichiarazione, e chi clona il repo senza un progetto Firebase
// deve poter compilare lo stesso — con l'app che avvisa in tempo reale ma non
// in background.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.lifecycle("google-services.json assente: build senza notifiche push")
}

// La firma di release arriva dall'ambiente, mai dal repository: perdere quella
// chiave significa non poter piu' aggiornare le installazioni esistenti, e
// averla nel repo significa che chiunque puo' pubblicare aggiornamenti a nome
// tuo. Senza, il build funziona lo stesso e produce un APK non firmato.
val keystorePath: String? = System.getenv("CRYLOG_KEYSTORE")
val keystorePassword: String? = System.getenv("CRYLOG_KEYSTORE_PASSWORD")
val keystoreAlias: String? = System.getenv("CRYLOG_KEY_ALIAS")
val keyPassword: String? = System.getenv("CRYLOG_KEY_PASSWORD")
val signingReady = !keystorePath.isNullOrBlank() &&
    file(keystorePath).exists() &&
    !keystorePassword.isNullOrBlank() &&
    !keystoreAlias.isNullOrBlank() &&
    !keyPassword.isNullOrBlank()

android {
    namespace = "it.biagini.crylog"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.biagini.crylog"
        // API 29 e' il minimo per la cattura audio concorrente ufficiale, su cui si regge
        // il design a microfono condiviso fra rilevamento rumore e streaming.
        minSdk = 29
        targetSdk = 36
        // Sovrascrivibili dalla riga di comando, cosi' una release puo' portare
        // il numero del suo tag invece di uno inciso nel file.
        versionCode = (findProperty("crylogVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("crylogVersionName") as String?) ?: "0.1.0"

        // Solo arm64. Le librerie native di WebRTC arrivano per quattro
        // architetture e sono la gran parte del peso dell'APK: con minSdk 29
        // un telefono a 32 bit e' una rarita', e tenerle tutte significa far
        // scaricare tre copie inutili a chiunque.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.lifecycle("chiave di firma assente: l'APK di release non sara' firmato")
            }
            // R8 spento per ora: le icone di Material inutilizzate resterebbero
            // dentro, ma accenderlo senza aver verificato le regole di WebRTC,
            // OkHttp e Firebase e' il modo classico di scoprire in produzione
            // che qualcosa e' stato rimosso di troppo.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.okhttp)
    implementation(libs.webrtc)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
