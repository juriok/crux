# Room generates implementations at build time; keep the no-arg ctors it needs.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { public static ** getInstance(...); }

# kotlinx.serialization: keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.rokjuric.crux.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rokjuric.crux.data.**$$serializer { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
