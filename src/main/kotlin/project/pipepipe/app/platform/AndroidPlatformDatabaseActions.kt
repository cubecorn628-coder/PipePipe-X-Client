package project.pipepipe.app.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import project.pipepipe.app.SharedContext
import project.pipepipe.database.AppDatabase
import project.pipepipe.database.Error_log
import project.pipepipe.database.Remote_playlists
import project.pipepipe.database.Streams
import project.pipepipe.database.Subscriptions

class AndroidPlatformDatabaseActions(private val context: Context): PlatformDatabaseActions {
    private lateinit var driver: AndroidSqliteDriver
    private fun createAppDatabase(driver: app.cash.sqldelight.db.SqlDriver): AppDatabase {
        return AppDatabase(
            driver = driver,
            error_logAdapter = Error_log.Adapter(
                service_idAdapter = IntColumnAdapter
            ),
            remote_playlistsAdapter = Remote_playlists.Adapter(
                service_idAdapter = IntColumnAdapter
            ),
            streamsAdapter = Streams.Adapter(
                service_idAdapter = IntColumnAdapter
            ),
            subscriptionsAdapter = Subscriptions.Adapter(
                service_idAdapter = IntColumnAdapter
            ),
        )
    }

    override fun initializeDatabase() {
        migrateDatabaseName()
        driver = AndroidSqliteDriver(
            schema = AppDatabase.Schema,
            context = context,
            name = "pipepipe.db",
            callback = object : AndroidSqliteDriver.Callback(AppDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.setForeignKeyConstraintsEnabled(true)
                }
            }
        )
        SharedContext.database = createAppDatabase(driver)
    }

    private fun migrateDatabaseName() {
        val settings = SharedContext.settingsManager
        if (settings.getBoolean("database_name_migrated", false)) return

        val oldDb = context.getDatabasePath("newpipe.db")
        if (oldDb.exists()) {
            val newDb = context.getDatabasePath("pipepipe.db")
            if (!newDb.exists()) {
                oldDb.renameTo(newDb)
                listOf("-journal", "-shm", "-wal").forEach { suffix ->
                    val oldFile = context.getDatabasePath("newpipe.db$suffix")
                    if (oldFile.exists()) {
                        oldFile.renameTo(context.getDatabasePath("pipepipe.db$suffix"))
                    }
                }
            }
        }
        settings.putBoolean("database_name_migrated", true)
    }

    override fun resetDatabase() {
        driver.close()
        initializeDatabase()
    }

    override fun verifyDatabase() {
        try {
            val verifyDriver = AndroidSqliteDriver(AppDatabase.Schema, context, "pipepipe.db")
            val database = createAppDatabase(verifyDriver)
            database.appDatabaseQueries.selectStreamsCount().executeAsOne()
            verifyDriver.close()
        } catch (e: Exception) {
            throw Exception("Imported database verification failed: ${e.message}", e)
        }
    }

    override fun getDatabaseBytes(): ByteArray? {
        val dbFile = context.getDatabasePath("pipepipe.db")
        return if (dbFile.exists()) dbFile.readBytes() else null
    }

    override fun writeDatabaseBytes(bytes: ByteArray) {
        val dbFile = context.getDatabasePath("pipepipe.db")
        dbFile.parentFile?.mkdirs()

        // Delete auxiliary files before writing new database bytes to prevent corruption
        listOf("-journal", "-shm", "-wal").forEach { suffix ->
            val auxFile = context.getDatabasePath("pipepipe.db$suffix")
            if (auxFile.exists()) auxFile.delete()
        }

        dbFile.writeBytes(bytes)
    }

    override fun runDatabaseMigration(importedVersion: Int) {
        val dbFile = context.getDatabasePath("pipepipe.db")
        val appSchemaVersion = AppDatabase.Schema.version.toInt()

        // If the imported database version is newer than the app's schema version,
        // we cap the version number to avoid potential crashes or upgrade issues.
        // The actual migration from older versions is handled automatically by SQLDelight 
        // when AndroidSqliteDriver is initialized in verifyDatabase().
        if (importedVersion > appSchemaVersion) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            db.version = appSchemaVersion
            db.close()
        }
    }

}