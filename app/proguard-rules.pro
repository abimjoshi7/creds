# SQLCipher ships native methods reached only via JNI.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Argon2Kt JNI bridge.
-keep class com.lambdapioneer.argon2kt.** { *; }

# zxcvbn loads its dictionaries as resources by reflection-free lookup, but keeps
# enum-shaped matchers that R8 can otherwise strip.
-keep class com.nulabinc.zxcvbn.** { *; }

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
