// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** The local collection database (ADR 0006). Schemas are exported to `android/app/schemas` for migrations. */
@Database(
    entities = [
        CardSetEntity::class,
        CardEntity::class,
        OwnedCopyEntity::class,
        CollectionEntity::class,
        CollectionCopyCrossRef::class,
        CachedPriceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CardIndexDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun copyDao(): CopyDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        private const val NAME = "cardindex.db"

        fun build(context: Context): CardIndexDatabase =
            Room.databaseBuilder(context.applicationContext, CardIndexDatabase::class.java, NAME).build()
    }
}
