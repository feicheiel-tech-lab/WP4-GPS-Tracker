# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Retrofit / Gson -------------------------------------------------------------
# These request/response DTOs have no @SerializedName annotations, so Gson maps
# JSON keys to them by field name via reflection. Keep the names intact or the
# server's requests/responses stop round-tripping once fields get obfuscated.
-keep class feicheiel.technologies.trackme.api.** { *; }
-keepattributes Signature, Exceptions, *Annotation*, InnerClasses, EnclosingMethod

# --- WorkManager Workers ----------------------------------------------------------
# WorkManager's default WorkerFactory instantiates Worker subclasses by reflection
# via their (Context, WorkerParameters) constructor; keep it reachable.
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}