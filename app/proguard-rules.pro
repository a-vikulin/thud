# Add project specific ProGuard rules here.

# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# gRPC / Protocol Buffers
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.ifit.glassos.** { *; }
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**

# Room database entities
-keep class io.github.avikulin.thud.data.entity.** { *; }
-keep class io.github.avikulin.thud.data.db.** { *; }

# Garmin FIT SDK
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
