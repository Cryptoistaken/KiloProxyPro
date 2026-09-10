# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# JNI native methods — only class referenced via registerNatives (System.kt -> system.cpp)
-keep class net.typeblog.socks.System { native <methods>; }

# hev-socks5-tunnel registers natives by class+method name at runtime
# (hev-jni.c JNI_OnLoad -> hev.htproxy.TProxyService); keep names exact.
-keep class hev.htproxy.TProxyService { *; }

# R8: javax.annotation classes are referenced by com.google.crypto.tink but not on compile classpath
-dontwarn javax.annotation.**
-keep class javax.annotation.** { *; }

# Keep AndroidX Preference classes (used via dynamic casts in PreferenceFragmentCompat)
-keep class androidx.preference.** { *; }

# R8: Kotlin coroutines internals (SpillingKt referenced by suspend lambdas)
-keep class kotlin.coroutines.jvm.internal.** { *; }
-dontwarn kotlin.coroutines.jvm.internal.**
