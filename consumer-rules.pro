# Public models are ordinary Kotlin data classes. Keep serialization metadata
# only for applications that enable shrinking and use serialized chart records.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature
-dontwarn kotlinx.serialization.**
