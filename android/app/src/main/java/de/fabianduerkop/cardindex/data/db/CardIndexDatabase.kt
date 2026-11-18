// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        ContributionEntity::class,
        PriceHistoryEntity::class,
        PriceHistoryFetchEntity::class,
        PriceAlertEntity::class,
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
    abstract fun contributionDao(): ContributionDao
    abstract fun priceHistoryDao(): PriceHistoryDao
    abstract fun priceAlertDao(): PriceAlertDao

    companion object {
        /** Bump together with a new entry in [MIGRATIONS]; the build exports `schemas/…/<VERSION>.json`. */
        const val VERSION = 3

        /** v2 (#17): the Pro price-history cache. Only new tables, so nothing to copy. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_history` (`cardId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`variant` TEXT NOT NULL, `month` TEXT NOT NULL, `days` TEXT NOT NULL, `amounts` TEXT NOT NULL, " +
                        "PRIMARY KEY(`cardId`, `source`, `variant`, `month`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_history_fetches` (`cardId` TEXT NOT NULL, `month` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cardId`, `month`))",
                )
            }
        }

        /** v3 (#76): Pro price alerts. A new table only. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_alerts` (`uuid` TEXT NOT NULL, `cardId` TEXT NOT NULL, " +
                        "`cardLanguage` TEXT NOT NULL, `source` TEXT NOT NULL, `variant` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, `thresholdMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `triggeredAt` INTEGER, `armed` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uuid`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_alerts_cardId` ON `price_alerts` (`cardId`)")
            }
        }

        /** Every migration since v1. SchemaMigrationTest runs each exported version through these. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
