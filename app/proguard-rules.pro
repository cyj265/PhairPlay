# ProGuard rules for PhairPlay release builds.
# Keep rules prevent ProGuard from removing or renaming code that is used
# via reflection or that must retain its original name for the AirPlay protocol.

# Keep Bouncy Castle — crypto classes are loaded by name via SPI
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Timber — logging classes
-keep class timber.log.** { *; }

# Keep Kotlin coroutine infrastructure
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep our main application classes
-keep class com.phairplay.** { *; }

# General Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
