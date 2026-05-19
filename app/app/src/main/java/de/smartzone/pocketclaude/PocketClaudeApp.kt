package de.smartzone.pocketclaude

import android.app.Activity
import android.app.Application
import android.os.Bundle
import de.smartzone.pocketclaude.data.AppContainer
import de.smartzone.pocketclaude.service.NotificationHelper

class PocketClaudeApp : Application() {
    lateinit var container: AppContainer
        private set

    /** True, solange mindestens eine Activity dieser App im Resumed-State ist. */
    @Volatile var isInForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        NotificationHelper.ensureChannels(this)
        registerActivityLifecycleCallbacks(ForegroundTracker())
    }

    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        private var resumedCount = 0
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            resumedCount++
            isInForeground = resumedCount > 0
        }
        override fun onActivityPaused(activity: Activity) {
            resumedCount = (resumedCount - 1).coerceAtLeast(0)
            isInForeground = resumedCount > 0
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
