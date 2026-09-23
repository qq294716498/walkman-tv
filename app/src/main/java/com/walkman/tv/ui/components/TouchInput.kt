package com.walkman.tv.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * TV Material Surface handles D-pad enter but does not receive pointer taps.
 * Keep its focus/key handling and add touch input for phones and touch-enabled TVs.
 */
fun Modifier.tvTouch(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = onLongClick?.let { action -> { action() } },
        )
    }
