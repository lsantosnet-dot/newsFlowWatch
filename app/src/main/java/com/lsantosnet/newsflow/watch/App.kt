package com.lsantosnet.newsflow.watch

import android.app.Application
import android.content.Context
import com.lsantosnet.newsflow.watch.data.NewsRepository

class App : Application() {
    lateinit var repository: NewsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NewsRepository(getSharedPreferences("config", Context.MODE_PRIVATE))
    }
}

val Context.repository: NewsRepository get() = (applicationContext as App).repository
