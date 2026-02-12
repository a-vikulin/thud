package io.github.avikulin.thud.domain.model

import io.github.avikulin.thud.R

enum class AndroidAction(val labelResId: Int) {
    MEDIA_PLAY_PAUSE(R.string.android_action_play_pause),
    MEDIA_NEXT(R.string.android_action_next_track),
    MEDIA_PREVIOUS(R.string.android_action_prev_track),
    VOLUME_UP(R.string.android_action_volume_up),
    VOLUME_DOWN(R.string.android_action_volume_down),
    MUTE(R.string.android_action_mute),
    BACK(R.string.android_action_back),
    HOME(R.string.android_action_home),
    RECENT_APPS(R.string.android_action_recent_apps),
}
