# Add project specific ProGuard rules here.

# Keep Room database entities and DAOs
-keep class com.cartracker.app.data.** { *; }
-keepclassmembers class com.cartracker.app.data.** { *; }

# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Compose
-dontwarn androidx.compose.**

# Keep broadcast receivers
-keep class com.cartracker.app.receiver.** { *; }

# Keep the service
-keep class com.cartracker.app.service.** { *; }

# Keep WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep the Application class
-keep class com.cartracker.app.CarTrackerApp { *; }
