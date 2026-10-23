# WebRTC usa JNI: conservar clases y miembros nativos.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.jarvis.**$$serializer { *; }
-keepclassmembers class com.example.jarvis.** { *** Companion; }

# Recursos Java de :core (tools.json, persona-es.md, máscara de tierra)
-keepdirectories

# Sherpa-ONNX (si se incluye) usa JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# osmdroid
-dontwarn org.osmdroid.**
