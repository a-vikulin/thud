package io.github.avikulin.thud.domain.model

import io.github.avikulin.thud.R

enum class RemoteAction(val labelResId: Int, val hasValue: Boolean = false) {
    SPEED_UP(R.string.remote_action_speed_up, hasValue = true),
    SPEED_DOWN(R.string.remote_action_speed_down, hasValue = true),
    INCLINE_UP(R.string.remote_action_incline_up, hasValue = true),
    INCLINE_DOWN(R.string.remote_action_incline_down, hasValue = true),
    BELT_START_PAUSE(R.string.remote_action_belt_start_pause),
    BELT_STOP(R.string.remote_action_belt_stop),
    NEXT_STEP(R.string.remote_action_next_step),
    PREV_STEP(R.string.remote_action_prev_step),
    TOGGLE_MODE(R.string.remote_action_toggle_mode),
}
