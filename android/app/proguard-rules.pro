# kotlinx.serialization: keep generated serializers for the API models.
-keepclassmembers @kotlinx.serialization.Serializable class dev.minicam.mobile.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
