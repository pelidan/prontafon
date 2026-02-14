# Jetpack Compose - keep all composables
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Hilt dependency injection
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.** 
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.prontafon.**$$serializer { *; }
-keepclassmembers class com.prontafon.** { *** Companion; }
-keepclasseswithmembers class com.prontafon.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**

# Crypto libraries (Tink, BouncyCastle)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Google Error Prone annotations (compile-time only, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**

# Play Core (deferred components - not used)
-dontwarn com.google.android.play.core.**

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
