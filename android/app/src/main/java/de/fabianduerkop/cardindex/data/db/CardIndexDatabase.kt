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
        WantedCardEntity::class,
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
    abstract fun wishlistDao(): WishlistDao

    companion object {
        /** Bump together with a new entry in [MIGRATIONS]; the build exports `schemas/…/<VERSION>.json`. */
        const val VERSION = 5

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

        /**
         * v4: the search key of the collector number ([CardEntity.numberKey], #124) in its own indexed column, filled
         * the way search compared it before (the next pack import writes it again); and the review draft on its item
         * (#141), new nullable columns where null is "not picked".
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `numberKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `cards` SET `numberKey` = UPPER(LTRIM(`localId`, '0'))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_numberKey` ON `cards` (`numberKey`)")
                listOf("draftRank INTEGER", "draftFinish TEXT", "draftEdition TEXT", "draftCondition TEXT", "draftQuantity INTEGER")
                    .forEach { db.execSQL("ALTER TABLE `review_items` ADD COLUMN $it") }
            }
        }

        /** v5 (#134): the wishlist, wanted prints without a copy. A new table only. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wanted_cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, `cardId` TEXT NOT NULL, `cardLanguage` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`cardId`, `cardLanguage`) REFERENCES `cards`(`id`, `language`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wanted_cards_uuid` ON `wanted_cards` (`uuid`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wanted_cards_cardId_cardLanguage` " +
                        "ON `wanted_cards` (`cardId`, `cardLanguage`)",
                )
            }
        }

        /** Every migration since v1. SchemaMigrationTest runs each exported version through these. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

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
