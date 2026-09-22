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

# ─── DLNA / UPnP (jUPnP) ───────────────────────────────────────────────
# jUPnP relies heavily on reflection: annotation-driven service binding,
# the seamless-style state-machine (dynamically generated classes), and
# registry lookups by class name. Everything must keep its exact name.
-keep class org.jupnp.** { *; }
-dontwarn org.jupnp.**

# DLNA renderer state machines (instantiated reflectively by jUPnP)
-keep class com.phairplay.dlna.renderer.** { *; }

# ─── Media3 / ExoPlayer ─────────────────────────────────────────────────
# Media3 ships its own consumer ProGuard rules; keep any reflection entry
# points it relies on.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
