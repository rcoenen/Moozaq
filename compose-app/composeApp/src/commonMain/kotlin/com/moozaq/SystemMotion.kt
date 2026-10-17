package com.moozaq

import androidx.compose.runtime.Composable

/**
 * True when the platform asks for reduced motion, so the equalizer bars freeze.
 * Android reads the system "Remove animations" setting; desktop has no equivalent.
 */
@Composable expect fun systemReducedMotion(): Boolean
