package com.picpocket.app.di

import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.local.dao.WorkflowDao
import com.picpocket.app.data.local.dao.WorkflowRunDao
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.repository.WorkflowRepositoryImpl
import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.action.DefaultActionRegistry
import com.picpocket.app.domain.workflow.engine.AppForegroundExecutor
import com.picpocket.app.domain.workflow.engine.ForegroundExecutor
import com.picpocket.app.domain.storage.FolderAccess
import com.picpocket.app.domain.storage.SafFolderAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkflowModule {

    @Provides
    @Singleton
    fun provideWorkflowDao(database: PicPocketDatabase): WorkflowDao = database.workflowDao()

    @Provides
    @Singleton
    fun provideWorkflowRunDao(database: PicPocketDatabase): WorkflowRunDao = database.workflowRunDao()

    @Provides
    @Singleton
    fun provideWorkflowRepository(impl: WorkflowRepositoryImpl): WorkflowRepository = impl

    @Provides
    @Singleton
    fun provideForegroundExecutor(impl: AppForegroundExecutor): ForegroundExecutor = impl

    @Provides
    @Singleton
    fun provideActionRegistry(impl: DefaultActionRegistry): ActionRegistry = impl

    @Provides
    @Singleton
    fun provideFolderAccess(impl: SafFolderAccess): FolderAccess = impl

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
