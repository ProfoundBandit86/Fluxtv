# Keep readable stack traces (Flux shows the last error on screen).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Flux's own classes are small; keep them whole (activities, services and receivers are also kept by the manifest).
-keep class com.fluxtv.app.** { *; }

# SMB (jcifs-ng) and its crypto load classes by name.
-keep class jcifs.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.servlet.**

# FFmpeg audio decoders: native code calls back into these classes.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }
-dontwarn org.jellyfin.**
