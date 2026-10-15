# Room, CameraX, ML Kit and kotlinx.serialization ship their own consumer rules; these cover what is ours.

# Operations, requests and backups are stored as JSON through kotlinx.serialization's generated serializers.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
-keepclassmembers @kotlinx.serialization.Serializable class dev.duardo.neotransfer.** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class dev.duardo.neotransfer.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.duardo.neotransfer.**$$serializer { *; }

# Stack traces from the field stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
