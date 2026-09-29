package com.picpocket.app.domain.workflow.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.picpocket.app.domain.workflow.model.ActionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs UI-bound actions on the main thread, one at a time, and only while the
 * app is in the foreground. Share/chooser actions start an Activity, which
 * Android forbids from the background.
 */
interface ForegroundExecutor {
    val isForeground: Boolean
    suspend fun run(block: suspend () -> ActionResult): ActionResult
}

@Singleton
open class AppForegroundExecutor @Inject constructor(
    private val app: Application,
) : ForegroundExecutor {

    private val mutex = Mutex()

    @Volatile
    private var startedActivities = 0

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override open val isForeground: Boolean
        get() = startedActivities > 0

    protected open val dispatcher: CoroutineDispatcher
        get() = Dispatchers.Main

    override suspend fun run(block: suspend () -> ActionResult): ActionResult {
        return mutex.withLock {
            if (!isForeground) {
                return@withLock ActionResult.Failure("App must be in the foreground for this action")
            }
            withContext(dispatcher) { block() }
        }
    }
}
