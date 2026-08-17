# MinuteWatcher R8/ProGuard Configuration

# AppSearch rules are provided by feature:semantic via consumerProguardFiles

# Hilt, ViewModel, and standard Android components (Activity, Service, etc.)
# are automatically handled by AGP and library-bundled consumer rules.
# DataStore and Protobuf also provide their own rules.

# General optimization settings
# -repackageclasses '' # Can be enabled for further size reduction if needed
-allowaccessmodification
