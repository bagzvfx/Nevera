package com.nevera.corporation

import android.app.Application
import com.nevera.corporation.data.lyrics.NeveraLyricAiEngine
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.sources.InnerTubeXResolver
import com.nevera.corporation.data.sources.YouTubeStreamResolver
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.data.store.NetworkStorageRepository
import com.nevera.corporation.download.OfflineDownloadManager

class NeveraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LibraryStore.init(this)
        NetworkStorageRepository.init(this)
        AccountSyncManager.init(this)
        NeveraLyricAiEngine.initCache(cacheDir)
        OfflineDownloadManager.init(this)
        InnerTubeXResolver.init(this)
        YouTubeStreamResolver.ensureInitialized()
    }
}
