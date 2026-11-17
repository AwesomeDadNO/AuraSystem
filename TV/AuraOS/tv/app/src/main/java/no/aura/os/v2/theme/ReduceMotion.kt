package no.aura.os.v2.theme

import androidx.compose.runtime.compositionLocalOf

/** Reduce Motion preference. No Setting drives it yet (default false); animations that honour it use zero-length transitions when true. */
val LocalReduceMotion = compositionLocalOf { false }
