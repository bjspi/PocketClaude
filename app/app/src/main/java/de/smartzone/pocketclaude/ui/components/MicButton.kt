package de.smartzone.pocketclaude.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.smartzone.pocketclaude.R

enum class MicState { Idle, Recording, Transcribing }

/**
 * Mic-Button für den Input-Bar. Visuelles Vokabular adaptiert von
 * WhisperTyper (pulsierendes Indikator-Element):
 *   - Idle: stilles Mikro-Icon
 *   - Recording: Mikro auf grünem Untergrund, zwei pulsierende Kreise
 *     drumherum (1.4 s Loop, gegeneinander versetzt → "Atmen")
 *   - Transcribing: Mikro durch Spinner ersetzt
 *
 * Tap toggle Idle↔Recording, Long-Press während Recording = Cancel
 * (silent discard, keine Transkription).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicButton(
    state: MicState,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(48.dp)
            .combinedClickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 24.dp),
                onClick = {
                    haptics.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                    onTap()
                },
                onLongClick = if (state == MicState.Recording) {
                    {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        onCancel()
                    }
                } else null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            MicState.Idle -> {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.voice_mic_idle_cd),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
                )
            }

            MicState.Recording -> {
                // Pulse-Animation: zwei konzentrische Halo-Kreise, 1400 ms
                // Loop, der zweite um 700 ms versetzt → optisches "Atmen".
                val transition = rememberInfiniteTransition(label = "mic-pulse")
                val pulseA by transition.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "pulseA",
                )
                val pulseB by transition.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing, delayMillis = 700),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "pulseB",
                )
                // Pulse-Kreise hinter dem Mic
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(1f + pulseA * 0.6f)
                        .alpha((1f - pulseA) * 0.55f)
                        .clip(CircleShape)
                        .background(PULSE_GREEN),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(1f + pulseB * 0.6f)
                        .alpha((1f - pulseB) * 0.55f)
                        .clip(CircleShape)
                        .background(PULSE_GREEN),
                )
                // Mic im grünen Kern, fest skaliert (kein Wackeln)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PULSE_GREEN),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.voice_mic_recording_cd),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            MicState.Transcribing -> {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Cancel-Indicator als Floating-Hint über dem Input während Recording —
 *  zeigt dem User, dass Long-Press abbricht. Wird vom Caller positioniert. */
@Composable
fun MicCancelHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }
}

// Grün analog WhisperTyper / Material "live recording" — kräftig genug
// um auf hellem und dunklem Hintergrund klar sichtbar zu sein.
private val PULSE_GREEN = Color(0xFF22C55E)
