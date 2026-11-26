# libtorrent4j: JNI code calls back into these classes by name.
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { *; }

# Settings names are resolved by reflection on the generated swig enums.
-keepnames class org.libtorrent4j.swig.settings_pack$** { *; }

# kotlinx.serialization (rules ship with the library; keep our serializers' companions just in case)
-keepclassmembers class com.graphite.torrent.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ZXing is used only for decoding QR codes.
-dontwarn com.google.zxing.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
