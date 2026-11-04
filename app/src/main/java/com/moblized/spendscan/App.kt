package com.moblized.spendscan

import android.app.Application
import com.moblized.spendscan.data.AppDatabase
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.DEFAULT_CATEGORIES
import com.moblized.spendscan.data.FALLBACK_CATEGORY
import com.moblized.spendscan.data.Prefs
import com.moblized.spendscan.data.SpendDao
import com.moblized.spendscan.scan.PhotoStore
import com.moblized.spendscan.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.open(this) }
    val dao: SpendDao get() = db.dao()
    val prefs: Prefs by lazy { Prefs(this) }
    val photos: PhotoStore by lazy { PhotoStore(this) }
    val updater: Updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        scope.launch { seedCategories(dao) }
    }

    companion object {
        suspend fun seedCategories(dao: SpendDao) {
            if (dao.categoryCount() > 0) return
            DEFAULT_CATEGORIES.forEachIndexed { i, name -> dao.insertCategory(Category(name = name, position = i)) }
            dao.insertCategory(Category(name = FALLBACK_CATEGORY, position = DEFAULT_CATEGORIES.size, fixed = true))
        }
    }
}
