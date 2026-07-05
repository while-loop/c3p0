package dev.whileloop.c3p0.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.whileloop.c3p0.domain.usecase.StepHistoryDataSource
import dev.whileloop.c3p0.health.HealthConnectManager

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {
    @Binds
    abstract fun bindStepHistoryDataSource(
        healthConnectManager: HealthConnectManager
    ): StepHistoryDataSource
}
