package com.picpocket.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.picpocket.app.data.workflow.WorkflowTriggerRegistry
import com.picpocket.app.debug.Tracing
import com.picpocket.app.debug.TracingConfig
import com.picpocket.app.domain.workflow.queue.WorkflowQueueScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PicPocketApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tracingConfig: TracingConfig
    @Inject lateinit var workflowTriggerRegistry: WorkflowTriggerRegistry
    @Inject lateinit var workflowQueueScheduler: WorkflowQueueScheduler

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Tracing.initialize(tracingConfig)
        workflowTriggerRegistry.start()
        // Resume anything that was deferred while offline. A queued share needs
        // the app in the foreground, so retry whenever the app is opened.
        workflowQueueScheduler.schedule()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) workflowQueueScheduler.requestDrainNow()
            },
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
