package com.picpocket.app.di

import javax.inject.Qualifier

/** The process-lifetime coroutine scope for background workflow dispatch. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
