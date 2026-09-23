package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Derive

/** A hand-written sealed type: @Derive adds fold and accessors without the Spec convention. */
@Derive
public sealed interface UiState {
    public data object Loading : UiState
    public data class Loaded(val user: User) : UiState

    @Derive
    public sealed interface Failed : UiState {
        public data object Offline : Failed
        public data class Server(val code: Int) : Failed
    }
}

public fun render(state: UiState): String = state.fold(
    onLoading = { "loading" },
    onLoaded = { "hello ${it.user.name}" },
    onFailed = { failed -> failed.fold(onOffline = { "offline" }, onServer = { "server error ${it.code}" }) },
)
