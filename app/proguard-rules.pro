# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep data binding classes (only generated binding classes for this app)
-keep class wtf.mazy.peel.databinding.** { *; }
-keep class androidx.databinding.ViewDataBinding { *; }
-keep class androidx.databinding.DataBindingUtil { *; }

# Keep all model classes for Gson serialization
-keep class wtf.mazy.peel.model.** { *; }
-keepclassmembers class wtf.mazy.peel.model.** { *; }

# Keep WebView Activity variants (critical for sandbox functionality!)
-keep class wtf.mazy.peel.activities.__WebViewActivity_* { *; }
-keep class wtf.mazy.peel.activities.WebViewActivity { *; }

# Keep all activities
-keep class * extends androidx.appcompat.app.AppCompatActivity { *; }
-keep class * extends android.app.Activity { *; }

# Keep reflection-based classes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Jsoup HTML parser - Keep only main classes used
-keeppackagenames org.jsoup.nodes
-keep class org.jsoup.Jsoup { *; }
-keep class org.jsoup.nodes.** { *; }
-keep class org.jsoup.select.Elements { *; }
-keep class org.jsoup.Connection { *; }
# Jsoup optional dependencies (re2j regex engine - not included)
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

# Gson - Keep only necessary classes
-keep class com.google.gson.Gson { *; }
-keep class com.google.gson.GsonBuilder { *; }
-keep class com.google.gson.JsonElement { *; }
-keep class com.google.gson.JsonObject { *; }
-keep class com.google.gson.JsonArray { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(***);
    *** get*();
}

# AndroidX - Keep only classes that extend core Android components
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.ViewModelProvider { *; }
-keep class androidx.lifecycle.LiveData { *; }
-keep class androidx.navigation.fragment.NavHostFragment { *; }

# Material Components - Keep only what's actually used
-keep class com.google.android.material.snackbar.Snackbar { *; }
-keep class com.google.android.material.floatingactionbutton.FloatingActionButton { *; }
-keep class com.google.android.material.card.MaterialCardView { *; }
-keep class com.google.android.material.appbar.AppBarLayout { *; }

# Biometric
-keep class androidx.biometric.BiometricPrompt { *; }
-keep class androidx.biometric.BiometricManager { *; }

# WebKit
-keep class androidx.webkit.WebViewCompat { *; }
-keep class androidx.webkit.WebSettingsCompat { *; }
