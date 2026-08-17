# Benchmark specific Proguard rules
# Currently identical to release but can be customized for instrumentation
-printconfiguration benchmark-config.txt

-keep @androidx.appsearch.annotation.Document class * { *; }
-keepclassmembers @androidx.appsearch.annotation.Document class * {
    <fields>;
    <init>(...);
}
-keep class **.$$__AppSearch__* { *; }
-keepnames class **.$$__AppSearch__* { *; }
