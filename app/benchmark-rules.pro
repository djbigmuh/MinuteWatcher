# Benchmark specific Proguard rules
# Currently identical to release but can be customized for instrumentation
-printconfiguration benchmark-config.txt

-keep class **.$$__AppSearch__* { *; }
