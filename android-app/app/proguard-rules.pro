# Regole per R8.
#
# Serve soprattutto a togliere le icone di Material inutilizzate: di questa
# libreria l'app ne usa sette, e senza shrinking finiscono nell'APK tutte,
# compilate a codice. Sono quasi cinquanta megabyte su sessantasei.
#
# Il rischio dello shrinking e' rimuovere qualcosa che viene cercato per nome
# invece che chiamato: R8 quei riferimenti non li vede.

# WebRTC.
#
# La libreria nativa chiama il codice Java per nome attraverso JNI: R8 non ha
# modo di accorgersene, e rimuoverebbe classi e metodi che sembrano morti. Il
# risultato non e' un errore di compilazione ma un crash al primo stream, o
# peggio un silenzio.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# jni_zero: il collante JNI di Chromium, che questa build di WebRTC usa e che
# vive in un package a sé.
#
# Tenere solo org.webrtc non basta, e il modo in cui si scopre e' brutale:
# l'app parte, si naviga, tutto funziona, e al primo tentativo di ascolto il
# processo muore di SIGTRAP dentro JNI_OnLoad. Nel logcat, una riga sola:
#     ClassNotFoundException: org.jni_zero.JniInit
# La libreria nativa quelle classi le cerca per nome, quindi per R8 sono
# codice morto.
-keep class org.jni_zero.** { *; }
-keepclassmembers class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# OkHttp e Okio portano le proprie regole, ma dichiarano anche classi
# opzionali che qui non ci sono: senza questo R8 si ferma sugli avvisi.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Firebase e Google Play Services arrivano con le loro regole. Questa copre
# solo i modelli serializzati, che vengono costruiti per riflessione.
-keepclassmembers class * {
    @com.google.firebase.encoders.annotations.Encodable <fields>;
}
