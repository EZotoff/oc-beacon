package dev.leonardo.ocbeacon.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.api.voice.VoiceWsClient
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.data.voice.AndroidVoiceAudioFocus
import dev.leonardo.ocbeacon.data.voice.AndroidVoicePlayer
import dev.leonardo.ocbeacon.data.voice.AndroidVoiceRecorder
import dev.leonardo.ocbeacon.data.voice.VoiceAudioEngine
import dev.leonardo.ocbeacon.data.voice.VoiceAudioFocus
import dev.leonardo.ocbeacon.data.voice.VoicePlayer
import dev.leonardo.ocbeacon.data.voice.VoiceRecorder
import dev.leonardo.ocbeacon.domain.voice.VoiceSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceScope

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides @Singleton @VoiceScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun provideClient(@VoiceScope scope: CoroutineScope): VoiceWsClient = VoiceWsClient(scope)

    @Provides @Singleton
    fun provideRecorder(@ApplicationContext context: Context, @VoiceScope scope: CoroutineScope): VoiceRecorder =
        AndroidVoiceRecorder(context, scope)

    @Provides @Singleton
    fun providePlayer(): VoicePlayer = AndroidVoicePlayer()

    @Provides @Singleton
    fun provideFocus(@ApplicationContext context: Context): VoiceAudioFocus = AndroidVoiceAudioFocus(context)

    @Provides @Singleton
    fun provideAudio(recorder: VoiceRecorder, player: VoicePlayer, focus: VoiceAudioFocus,
        @VoiceScope scope: CoroutineScope, client: VoiceWsClient): VoiceAudioEngine =
        VoiceAudioEngine(recorder, player, focus, scope, client::sendAudio)

    @Provides @Singleton
    fun provideRepository(client: VoiceWsClient, audio: VoiceAudioEngine,
        @VoiceScope scope: CoroutineScope, cache: SupervisorSnapshotCache): VoiceSessionRepository =
        VoiceSessionRepository(client, audio, scope) { cache.snapshots.value.values.lastOrNull() }
}
