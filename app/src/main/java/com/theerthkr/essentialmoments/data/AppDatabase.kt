package com.theerthkr.essentialmoments.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Process-wide Room database.
 *
 * Only the vector index lives here. The build is prerelease, so schema changes
 * fall back to a destructive migration: embeddings are derived data and the
 * indexer can always regenerate them.
 */
@Database(
    entities = [ImageIndexEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(FloatArrayConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageIndexDao(): ImageIndexDao

    companion object {
        private const val DATABASE_NAME = "essential_moments.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
