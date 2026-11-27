# Keep Room & SQLCipher classes intact during R8 minification
-keep class net.sqlcipher.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn net.sqlcipher.**
-dontwarn org.slf4j.**
