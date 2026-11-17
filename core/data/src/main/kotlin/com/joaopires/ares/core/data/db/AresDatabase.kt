// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

internal class Converters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

@Database(
    entities = [
        SectionEntity::class,
        FeedEntity::class,
        EntryEntity::class,
        EntryFts::class,
        EnclosureEntity::class,
        FilterEntity::class,
        TombstoneEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
public abstract class AresDatabase : RoomDatabase() {
    internal abstract fun sectionDao(): SectionDao
    internal abstract fun feedDao(): FeedDao
    internal abstract fun entryDao(): EntryDao
    internal abstract fun filterDao(): FilterDao

    public companion object {
        public fun create(context: Context): AresDatabase =
            Room.databaseBuilder(context.applicationContext, AresDatabase::class.java, "ares.db").build()
    }
}
