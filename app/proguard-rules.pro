# Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ftpflix.data.remote.**$$serializer { *; }
-keepclassmembers class com.ftpflix.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.ftpflix.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.ftpflix.sources.hyper.**$$serializer { *; }
-keepclassmembers class com.ftpflix.sources.hyper.** {
    *** Companion;
}
-keepclasseswithmembers class com.ftpflix.sources.hyper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions, InnerClasses, EnclosingMethod
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class com.squareup.okhttp3.** { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.scopes.* <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Media3
-keep class androidx.media3.** { *; }

# Keep our app model classes used with Gson-free serialization
-keep class com.ftpflix.data.model.** { *; }

# Strip verbose logging from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
