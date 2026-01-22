package project.pipepipe.app

import android.app.Application
import android.content.SharedPreferences
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import project.pipepipe.extractor.Router
import project.pipepipe.app.mediasource.MediaCacheProvider
import project.pipepipe.app.service.NotificationHelper
import project.pipepipe.app.service.StreamsNotificationManager
import project.pipepipe.app.database.DatabaseOperations
import project.pipepipe.shared.downloader.Downloader
import project.pipepipe.shared.infoitem.SupportedServiceInfo
import project.pipepipe.shared.job.SupportedJobType
import project.pipepipe.app.helper.executeJobFlow
import project.pipepipe.app.helper.SettingsManager
import project.pipepipe.app.helper.SettingsMigrator
import project.pipepipe.shared.state.Cache4kSessionManager
import project.pipepipe.app.viewmodel.VideoDetailViewModel
import project.pipepipe.app.download.DownloadManager
import project.pipepipe.app.download.DownloadManagerHolder
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import com.russhwolf.settings.SettingsListener
import com.russhwolf.settings.SharedPreferencesSettings
import dev.icerock.moko.resources.desc.StringDesc
import project.pipepipe.app.platform.AndroidPlatformDatabaseActions

class PipePipeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val handler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runBlocking {
                runCatching {
                    DatabaseOperations.insertErrorLog(throwable.stackTraceToString(), "UNKNOWN", "UNKNOWN_999")
                }
            }
            handler?.uncaughtException(thread, throwable)
        }
        val preferencesName = "${packageName}_preferences"
        val sharedPrefs = getSharedPreferences(preferencesName, MODE_PRIVATE)

        // Initialize SharedContext
        SharedContext.androidVersion = Build.VERSION.SDK_INT
        SharedContext.isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        SharedContext.downloader = Downloader(HttpClient(OkHttp))
        SharedContext.settingsManager = SettingsManager(
            onGetStringSet = { key, defaultValue ->
                // 返回一个不可变的副本或新实例，防止外部修改影响 SharedPreferences 内部缓存
                sharedPrefs.getStringSet(key, defaultValue)?.toSet() ?: defaultValue
            },
            onPutStringSet = { key, value ->
                // 强制 .toSet() 产生一个新实例，确保 SharedPreferences 识别到变化并正确提交
                sharedPrefs.edit().putStringSet(key, value.toSet()).apply()
            },
            onAddStringSetListener = { key, defaultValue, callback ->
                var prev = sharedPrefs.getStringSet(key, defaultValue)?.toSet()

                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
                    if (k == key) {
                        val current = p.getStringSet(key, defaultValue)
                        // 此时对比的是快照，即使用户手动修改了原来的 Set 引用，这里也能检测到
                        if (prev != current) {
                            val nextSnapshot = current?.toSet()
                            callback(nextSnapshot ?: defaultValue)
                            prev = nextSnapshot
                        }
                    }
                }

                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)

                object : SettingsListener {
                    override fun deactivate() {
                        sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                    }
                }
            }
        )
        SharedContext.sessionManager = Cache4kSessionManager()
        SharedContext.sharedVideoDetailViewModel = VideoDetailViewModel()
        SharedContext.serverRequestHandler = Router::execute

        SharedContext.settingsManager.getString("app_language_key").let {
            if (it != "system") {
                StringDesc.localeType = StringDesc.LocaleType.Custom(it)
            }
        }

        // Initialize Media and Notifications
        MediaCacheProvider.init(this)
        NotificationHelper.initNotificationChannels(this)
        SharedContext.platformDatabaseActions = AndroidPlatformDatabaseActions(this)
        SharedContext.platformDatabaseActions.initializeDatabase()

        // Clean up cancelled downloads (legacy status)
        GlobalScope.launch {
            runCatching { DatabaseOperations.deleteAllCancelledDownloads() }
        }

        // Initialize youtubedl-android and FFmpeg
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.d("PipePipeApp", "YoutubeDL and FFmpeg initialized successfully")
            GlobalScope.launch {
                runCatching{ YoutubeDL.updateYoutubeDL(this@PipePipeApplication) }
            }
        } catch (e: Exception) {
            Log.e("PipePipeApp", "Failed to initialize YoutubeDL/FFmpeg", e)
        }

        // Initialize DownloadManager
        val downloadManager = DownloadManager(this)
        DownloadManagerHolder.initialize(downloadManager)
        Log.d("PipePipeApp", "DownloadManager initialized")

        // Schedule streams notification periodic work
        StreamsNotificationManager.schedulePeriodicWork(this)

        // Initialization
        SettingsMigrator.migrate()
        GlobalScope.launch {
            SharedContext.initializeSupportedServices()
        }
    }
}
