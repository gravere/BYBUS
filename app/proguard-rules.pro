# Add project specific ProGuard rules here.
# VirtualApp/native/hidden APIs use string names and reflective fields. Shrink UI/dependencies,
# preserving the entire container boundary and bridge signatures.
-keep class com.lody.** { *; }
-keep class com.xdja.** { *; }
-keep class mirror.** { *; }
-keep class android.** { *; }
-keep class com.google.** { *; }
# These four compile-only bridge types are supplied by the guest game's classloader.
# Packaging a second GMS Task implementation in the host breaks Firebase C++ bridging.
-dontwarn com.google.android.gms.tasks.OnCanceledListener
-dontwarn com.google.android.gms.tasks.OnFailureListener
-dontwarn com.google.android.gms.tasks.OnSuccessListener
-dontwarn com.google.android.gms.tasks.Task
-keep class org.lsposed.** { *; }
-keep class com.example.limbuszhcn.LauncherViewModel { public <init>(android.app.Application); }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class ** implements java.io.Serializable { *; }
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
