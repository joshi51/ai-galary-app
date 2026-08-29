package com.localphotoai.photomanager.di

import com.localphotoai.photomanager.core.common.AppDispatchers
import com.localphotoai.photomanager.core.common.Logger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger

    @Binds
    @Singleton
    abstract fun bindAppDispatchers(impl: DefaultAppDispatchers): AppDispatchers
}
