# R8 rules for a release build.

# The ldk-node bindings reach the Rust library through UniFFI, JNA and reflection:
# the generated classes, the callback interfaces they implement and everything under
# org.lightningdevkit.ldknode are looked up by name at runtime, so none of it may be
# renamed, shrunk or inlined away.
-keep class org.lightningdevkit.ldknode.** { *; }
-keep interface org.lightningdevkit.ldknode.** { *; }
-keep class * implements org.lightningdevkit.ldknode.** { *; }
-keepclassmembers class org.lightningdevkit.ldknode.** { *; }

# JNA loads its native dispatch and the Rust `cdylib` by name.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Compose ships consumer rules for its runtime; nothing extra is kept for it here.

# The wallet's own data holders are only used directly, but keeping them makes a
# minified crash report readable and costs a few hundred bytes.
-keep class com.corejbh.zerowallet.core.BackupManifest { *; }
-keep class com.corejbh.zerowallet.core.ChannelBackupExport { *; }
-keep class com.corejbh.zerowallet.core.WalletState { *; }

# Optional dependencies of JNA and the Kotlin runtime that are not on the classpath.
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
