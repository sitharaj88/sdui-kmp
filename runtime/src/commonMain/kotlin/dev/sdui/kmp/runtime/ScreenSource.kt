package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Screen
import kotlinx.coroutines.flow.Flow

/** A reactive source of [Screen] trees. Implementations exist per transport (HTTP, WebSocket). */
public interface ScreenSource {
    public val screen: Flow<ScreenState>
    public fun retry()
}

/** What the host is currently showing. */
public sealed interface ScreenState {
    public data object Loading : ScreenState
    public data class Error(public val error: Throwable) : ScreenState
    public data class Ready(public val screen: Screen) : ScreenState
}
