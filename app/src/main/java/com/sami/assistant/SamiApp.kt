package com.sami.assistant

import android.app.Application
import com.sami.assistant.core.AppContainer

class SamiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.sami.assistant.system.BackupManager.restoreIfPresent(this) // after a reinstall, load the PC backup first
        container = AppContainer(this)
        com.sami.assistant.system.Timetable.scheduleAll(this)
    }
}
