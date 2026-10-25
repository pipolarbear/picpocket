package com.picpocket.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.workflow.WorkflowTriggerRegistry
import com.picpocket.app.debug.Tracing
import com.picpocket.app.debug.TracingConfig
import com.picpocket.app.di.ApplicationScope
import com.picpocket.app.domain.storage.TrashSettings
import com.picpocket.app.domain.workflow.engine.WorkflowCache
import com.picpocket.app.domain.workflow.queue.WorkflowQueueScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class PicPocketApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tracingConfig: TracingConfig
    @Inject lateinit var workflowTriggerRegistry: WorkflowTriggerRegistry
    @Inject lateinit var workflowQueueScheduler: WorkflowQueueScheduler
    @Inject lateinit var documentRepository: DocumentRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Tracing.initialize(tracingConfig)
        workflowTriggerRegistry.start()
        // Resume anything that was deferred while offline. A queued share needs
        // the app in the foreground, so retry whenever the app is opened.
        workflowQueueScheduler.schedule()
        appScope.launch {
            documentRepository.purgeTrash(TrashSettings.read(this@PicPocketApp))
            WorkflowCache.clean(File(cacheDir, "workflow"))
        }
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
