package project.pipepipe.app

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dev.icerock.moko.resources.desc.StringDesc
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import project.pipepipe.app.database.DatabaseOperations
import project.pipepipe.app.download.DownloadManager
import project.pipepipe.app.download.DownloadManagerHolder
import project.pipepipe.app.helper.SettingsManager
import project.pipepipe.app.helper.SettingsMigrator
import project.pipepipe.app.mediasource.MediaCacheProvider
import project.pipepipe.app.platform.AndroidPlatformDatabaseActions
import project.pipepipe.app.service.NotificationHelper
import project.pipepipe.app.service.StreamsNotificationManager
import project.pipepipe.app.viewmodel.VideoDetailViewModel
import project.pipepipe.extractor.Router
import project.pipepipe.shared.downloader.Downloader
import project.pipepipe.shared.state.Cache4kSessionManager

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

        // Initialize SharedContext
        SharedContext.androidVersion = Build.VERSION.SDK_INT
        SharedContext.isTv = isTV()
        SharedContext.downloader = Downloader(HttpClient(OkHttp))
        SharedContext.settingsManager = SettingsManager()
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
    fun isTV(): Boolean {
        return try{
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager?
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                    || packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || packageManager.hasSystemFeature("amazon.hardware.fire_tv")
                    ||
                    (isBatteryAbsent() && !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                            && packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                            && packageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET))
        } catch (e: Exception) {
            false
        }
    }
    fun isBatteryAbsent(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        return (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0) == 0
    }
}
