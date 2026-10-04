package com.rokjuric.crux

import android.app.Application
import com.rokjuric.crux.data.CruxDb
import com.rokjuric.crux.data.Repo
import com.rokjuric.crux.data.Settings
import com.rokjuric.crux.sync.SyncWorker
import com.rokjuric.crux.workout.RestTimerService

class CruxApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this, CruxDb.open(this), Settings(this))
        RestTimerService.createChannels(this)
        SyncWorker.schedulePeriodic(this)
    }
}
