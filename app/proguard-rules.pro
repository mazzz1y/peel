# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep data binding classes (only generated binding classes for this app)
-keep class wtf.mazy.peel.databinding.** { *; }
-keep class androidx.databinding.ViewDataBinding { *; }
-keep class androidx.databinding.DataBindingUtil { *; }

# Keep model classes for Room @Embedded and kotlinx.serialization
-keep class wtf.mazy.peel.model.** { *; }
-keepclassmembers class wtf.mazy.peel.model.** { *; }

# Keep BrowserActivity
-keep class wtf.mazy.peel.activities.BrowserActivity { *; }

# Keep all activities
-keep class * extends androidx.appcompat.app.AppCompatActivity { *; }
-keep class * extends android.app.Activity { *; }

# Keep reflection-based classes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

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


# Material Components - Keep only what's actually used
-keep class com.google.android.material.snackbar.Snackbar { *; }
-keep class com.google.android.material.floatingactionbutton.FloatingActionButton { *; }
-keep class com.google.android.material.card.MaterialCardView { *; }
-keep class com.google.android.material.appbar.AppBarLayout { *; }

# Media playback
-keep class wtf.mazy.peel.media.MediaPlaybackService* { *; }

-keep class wtf.mazy.peel.browser.DownloadService { *; }

# Biometric
-keep class androidx.biometric.BiometricPrompt { *; }
-keep class androidx.biometric.BiometricManager { *; }


