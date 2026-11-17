# kotlinx.serialization : conserver les sérialiseurs générés.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.github.overnite22.relay.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.overnite22.relay.**$$serializer { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
