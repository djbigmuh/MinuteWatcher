# AppSearch keep rules for feature:semantic
-keep @androidx.appsearch.annotation.Document class * { *; }
-keepclassmembers @androidx.appsearch.annotation.Document class * {
    <fields>;
    <init>(...);
}
-keep class **.$$__AppSearch__* { *; }
