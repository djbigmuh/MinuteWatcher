# MinuteWatcher R8/ProGuard Configuration

# AppSearch
# Preserves classes and members annotated with @Document for reflection-based indexing
-keep @androidx.appsearch.annotation.Document class * { *; }
-keepclassmembers @androidx.appsearch.annotation.Document class * {
    <fields>;
    <init>(...);
}
-keep class **.$$__AppSearch__* { *; }

# Hilt, ViewModel, and standard Android components (Activity, Service, etc.)
# are automatically handled by AGP and library-bundled consumer rules.
# DataStore and Protobuf also provide their own rules.

# General optimization settings
# -repackageclasses '' # Can be enabled for further size reduction if needed
-allowaccessmodification
