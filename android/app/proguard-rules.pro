# Keep line numbers for readable Play Console stack traces (deobfuscated via mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room databases are created reflectively (Room.databaseBuilder, and androidx.startup for WorkManager).
# R8 in AGP 9 can strip the generated *_Impl no-arg constructor, which crashes release builds at start.
# See ~/knowledge/concepts/android-play-release-fallen.md.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit discovers its components through manifest meta-data and creates each ComponentRegistrar
# reflectively. In R8 full mode the library rules keep the classes but not their constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
