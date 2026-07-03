package dev.whileloop.c3p0.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.whileloop.c3p0.ble.manager.GarminManagerImpl
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.manager.WalkingPadManagerImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindTreadmillManager(impl: WalkingPadManagerImpl): TreadmillManager

    @Binds
    @Singleton
    abstract fun bindHeartRateManager(impl: GarminManagerImpl): HeartRateManager

    companion object {
        @Provides
        @Singleton
        fun provideContext(@ApplicationContext context: Context): Context = context
    }
}
