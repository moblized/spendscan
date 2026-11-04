package com.moblized.spendscan

import android.app.Application
import com.moblized.spendscan.data.AppDatabase
import com.moblized.spendscan.data.Prefs
import com.moblized.spendscan.data.SpendDao
import com.moblized.spendscan.scan.PhotoStore
import com.moblized.spendscan.update.Updater

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.open(this) }
    val dao: SpendDao get() = db.dao()
    val prefs: Prefs by lazy { Prefs(this) }
    val photos: PhotoStore by lazy { PhotoStore(this) }
    val updater: Updater by lazy { Updater(this) }
}
