package com.picpocket.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.picpocket.app.data.workflow.WorkflowTriggerRegistry
import com.picpocket.app.debug.Tracing
import com.picpocket.app.debug.TracingConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PicPocketApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tracingConfig: TracingConfig
    @Inject lateinit var workflowTriggerRegistry: WorkflowTriggerRegistry

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Tracing.initialize(tracingConfig)
        workflowTriggerRegistry.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
