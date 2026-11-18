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

# LiteRT 2.x: liblitert_jni looks up LiteRtException, TensorType, TensorType$Layout/$ElementType and
# TensorBufferRequirements by name and calls their constructors. The AAR's consumer rules cover only the old
# org.tensorflow.lite annotations, so R8 renamed them and the first failing call (GPU compile on the emulator)
# aborted the process ("Failed to find LiteRtException class") instead of throwing (#88). The API is small; keep it.
-keep class com.google.ai.edge.litert.** { *; }

# OpenCV ships no consumer rules. libopencv_java looks up CvException (thrown from native code) and MatOfInt by
# name and reads Mat.nativeObj.
-keep class org.opencv.core.CvException { *; }
-keep class org.opencv.core.MatOfInt { *; }
-keep class org.opencv.core.Mat { long nativeObj; }
