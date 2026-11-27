package dev.yesil.launchsoul.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.tx
import kotlin.reflect.KMutableProperty1
import dev.yesil.launchsoul.data.Prefs

private class Tune(val p: KMutableProperty1<Prefs, Int>, val def: Int, val range: IntRange, val unit: String, val title: Int, val sub: Int)

private val tunes = listOf(
    Tune(Prefs::tSwipe, 40, 16..120, "dp", R.string.tune_tSwipe, R.string.tune_tSwipe_sub),
    Tune(Prefs::tPage, 24, 8..80, "dp", R.string.tune_tPage, R.string.tune_tPage_sub),
    Tune(Prefs::tHold, 400, 200..1000, "ms", R.string.tune_tHold, R.string.tune_tHold_sub),
    Tune(Prefs::tDouble, 250, 120..600, "ms", R.string.tune_tDouble, R.string.tune_tDouble_sub),
    Tune(Prefs::tComing, 1200, 300..3000, "ms", R.string.tune_tComing, R.string.tune_tComing_sub),
    Tune(Prefs::tRescue, 800, 0..2000, "ms", R.string.tune_tRescue, R.string.tune_tRescue_sub),
    Tune(Prefs::tRecentsGap, 400, 0..1500, "ms", R.string.tune_tRecentsGap, R.string.tune_tRecentsGap_sub),
    Tune(Prefs::tSettle, 80, 0..500, "ms", R.string.tune_tSettle, R.string.tune_tSettle_sub),
    Tune(Prefs::tEditPoll, 500, 200..3000, "ms", R.string.tune_tEditPoll, R.string.tune_tEditPoll_sub),
    Tune(Prefs::tTop, 44, 0..150, "dp", R.string.tune_tTop, R.string.tune_tTop_sub),
    Tune(Prefs::tBottom, 32, 0..120, "dp", R.string.tune_tBottom, R.string.tune_tBottom_sub),
    Tune(Prefs::tEdgeSwipe, 48, 16..150, "dp", R.string.tune_tEdgeSwipe, R.string.tune_tEdgeSwipe_sub),
)

/** The timings and distances the gestures use; for fine tuning on a phone that behaves differently. */
@Composable
fun TuningScreen() {
    val p = LocalContext.current.app.prefs
    var reset by remember { mutableIntStateOf(0) }
    Page(tx(R.string.tuning)) {
        Note(tx(R.string.tuning_note))
        androidx.compose.runtime.key(reset) {
            Card {
                for (t in tunes) {
                    Number(tx(t.title), t.range, { t.p.get(p) }, { t.p.set(p, it) }) {
                        "$it ${t.unit}" + if (it == t.def) "" else " · " + tx(R.string.tune_default, t.def)
                    }
                    Note(tx(t.sub))
                }
            }
        }
        Big(tx(R.string.tune_reset)) {
            for (t in tunes) t.p.set(p, t.def)
            reset++
        }
    }
}
