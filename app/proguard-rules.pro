# Hilt / Dagger
-keep class * extends androidx.lifecycle.ViewModel
-keep class * extends android.app.Service
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# DataStore
-keep class androidx.datastore.preferences.core.Preferences { *; }

# AppSearch
-keep @androidx.appsearch.annotation.Document class * { *; }
-keepclassmembers @androidx.appsearch.annotation.Document class * {
    <fields>;
}

# ProtoBuf (used by DataStore)
-keep class com.google.protobuf.** { *; }
