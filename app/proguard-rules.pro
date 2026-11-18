# Keep the UniFFI/JNA bridge of ldk-node working when minification is enabled.
-keep class org.lightningdevkit.ldknode.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
