# R8 keep rules.
#
# Only what reflection reaches. Everything else in this app is called directly and R8 can see it,
# which is the point of turning minification on at all.

# ONNX Runtime and MediaPipe both bridge to native code by name. A renamed class is a crash at the
# JNI boundary, and one that only happens on a release build — the worst kind to find late.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn ai.onnxruntime.**

# kotlinx.serialization generates serializers and looks them up reflectively. Losing one turns
# saving a project into a runtime failure with no compile-time warning at all.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ir.pixellab.** {
    *** Companion;
}
-keepclasseswithmembers class ir.pixellab.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ir.pixellab.core.model.**$$serializer { *; }
-keep class ir.pixellab.core.model.** { *; }
