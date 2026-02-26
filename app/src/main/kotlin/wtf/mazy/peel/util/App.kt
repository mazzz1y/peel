package wtf.mazy.peel.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.db.AppDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        DataManager.instance.initialize(applicationContext)
        SandboxManager.initialize(AppDatabase.getInstance(applicationContext).sandboxSlotDao())
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set
    }
}
