package com.singularity.todo.core.platform

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android actual of [systemAnimationDurationScale].
 *
 * `ValueAnimator.areAnimatorsEnabled()` is the platform's own answer, and it is
 * the same answer Compose gives its animations: it is false when the system
 * animator scale is zero, which is what "Remove animations" writes. It is API
 * 26, and the module's `minSdk` is 26, so no version guard is needed — and the
 * obvious alternative, `getDurationScale()`, is hidden API and is not called.
 *
 * The setting can also hold a fraction (half speed), which still means
 * animations are on — so this reports `1f` for every enabled scale and `0f`
 * only for the one that turns them off.
 */
actual fun systemAnimationDurationScale(): Float = animationDurationScaleFor(ValueAnimator.areAnimatorsEnabled())

/**
 * Android actual of [rememberSystemAnimationDurationScale].
 *
 * "Remove animations" is one tap away in the accessibility settings, and it does
 * not restart the process: an answer read once at startup outlives the user's
 * decision, and the spinner keeps turning for someone who just asked it to stop.
 * So the value is a state, and it follows the setting.
 *
 * The notification is a content observer on `ANIMATOR_DURATION_SCALE` — the row
 * that the developer option and the accessibility toggle both write — rather than
 * a per-frame poll. A poll would re-read a global every frame to learn something
 * that changes a handful of times in a device's life.
 *
 * ## One observer per call site
 *
 * Each composition that calls this installs its own [ContentObserver] and its own
 * main-looper [Handler], and unregisters on dispose. The fact being observed is
 * process-wide, so the cheaper shape as readers multiply is one observer at the shell
 * feeding a single provided local; #2486 tracks that change.
 */
@Composable
actual fun rememberSystemAnimationDurationScale(): Float {
    val context = LocalContext.current
    val scale: MutableState<Float> = remember(context) { mutableStateOf(systemAnimationDurationScale()) }

    DisposableEffect(context) {
        val resolver = context.applicationContext.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale.value = systemAnimationDurationScale()
                }
            }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(uri, false, observer)
        // Re-read once now: the setting may have changed between the remember above
        // and the registration below, and that change would have had no observer.
        scale.value = systemAnimationDurationScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return scale.value
}
