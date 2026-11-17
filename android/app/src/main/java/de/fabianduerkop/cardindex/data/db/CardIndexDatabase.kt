// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/** The local collection database (ADR 0006). Schemas are exported to `android/app/schemas` for migrations. */
@Database(
    entities = [
        CardSetEntity::class,
        CardEntity::class,
        OwnedCopyEntity::class,
        CollectionEntity::class,
        CollectionCopyCrossRef::class,
        CachedPriceEntity::class,
        ReviewItemEntity::class,
        ReviewCandidateEntity::class,
    ],
    version = CardIndexDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CardIndexDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun copyDao(): CopyDao
    abstract fun collectionDao(): CollectionDao
    abstract fun reviewDao(): ReviewDao

    companion object {
        /** Bump together with a new entry in [MIGRATIONS]; the build exports `schemas/…/<VERSION>.json`. */
        const val VERSION = 1

        /** Every migration since v1. SchemaMigrationTest runs each exported version through these. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        private const val NAME = "cardindex.db"

        @Volatile
        private var instance: CardIndexDatabase? = null

        /** The app's one database; Room's invalidation tracking only works when every screen shares it. */
        fun get(context: Context): CardIndexDatabase =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        fun build(context: Context): CardIndexDatabase =
            Room.databaseBuilder(context.applicationContext, CardIndexDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
