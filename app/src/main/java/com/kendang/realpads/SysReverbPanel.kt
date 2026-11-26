package com.kendang.realpads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------------
// REVERB AUDIO sebagai BAGIAN panel mixer (bukan dialog/tombol terpisah). Memakai komponen yang sama dengan bagian lain
// (MixSection, GroupCard, KnobTile, MeterTile), jadi warna, ukuran, dan perilaku sentuhnya identik.
//   - saklar di judul bagian = nyalakan/matikan reverb di pemutar YouTube (tanpa izin apa pun, tanpa rekam layar)
//   - kartu REVERB dan DELAY punya saklar sendiri; knob KIRIM per band (SUB/LOW/MID/HIGH) ke reverb dan ke delay
// Susunan LEBAR (layar utama): satu pita dua baris di bawah MIKROFON/EQ. Susunan TEGAK (floating): kartu bertumpuk.
// ---------------------------------------------------------------------------------------------------------------------

/** Pengaturan yang dipakai bersama layar utama & floating (satu sumber, tersimpan ke SysReverbStore). */
object SysReverbUi {
    var s: SysReverbSettings by mutableStateOf(SysReverbSettings())
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = SysReverbStore.load(context.applicationContext)
            loaded = true
        }
    }

    fun update(context: Context, f: (SysReverbSettings) -> SysReverbSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        // Simpan ke disk setelah geseran berhenti (bukan tiap gerakan); kirim ke halaman digabung di YtReverb.pushNow.
        val app = context.applicationContext
        Coalesce.debounce("sysreverb.save", 400L) { SysReverbStore.save(app, s) }
        YtReverb.push(n)
    }
}

private class SysKnob(
    val label: String,
    val value: Float,
    val display: String,
    val reset: Float,
    val onChange: (Float) -> Unit
)

private val Defaults = SysReverbSettings()

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

private fun setBand(list: List<Float>, i: Int, v: Float): List<Float> = list.toMutableList().also { it[i] = v }

private fun reverbKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = listOf(
    SysKnob("WET", s.wet, pct(s.wet), Defaults.wet) { v -> SysReverbUi.update(c) { it.copy(wet = v) } },
    SysKnob("ROOM", s.room, pct(s.room), Defaults.room) { v -> SysReverbUi.update(c) { it.copy(room = v) } },
    SysKnob("DAMP", s.damp, pct(s.damp), Defaults.damp) { v -> SysReverbUi.update(c) { it.copy(damp = v) } },
    SysKnob("WIDTH", s.width, pct(s.width), Defaults.width) { v -> SysReverbUi.update(c) { it.copy(width = v) } },
    SysKnob("CESS", s.cess, pct(s.cess), Defaults.cess) { v -> SysReverbUi.update(c) { it.copy(cess = v) } },
    SysKnob("PRE-DLY", s.preDelay, "${(s.preDelay * 80).roundToInt()} ms", Defaults.preDelay) { v ->
        SysReverbUi.update(c) { it.copy(preDelay = v) }
    },
)

private fun delayKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = listOf(
    SysKnob("DELAY", s.echoDelay, "${(50 + s.echoDelay * 550).roundToInt()} ms", Defaults.echoDelay) { v ->
        SysReverbUi.update(c) { it.copy(echoDelay = v) }
    },
    SysKnob("FEEDBACK", s.echoFeedback, pct(s.echoFeedback), Defaults.echoFeedback) { v ->
        SysReverbUi.update(c) { it.copy(echoFeedback = v) }
    },
    SysKnob("MIX", s.echoMix, pct(s.echoMix), Defaults.echoMix) { v -> SysReverbUi.update(c) { it.copy(echoMix = v) } },
)

private val BandNames = listOf("SUB", "LOW", "MID", "HIGH")

private fun reverbSendKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = BandNames.mapIndexed { i, name ->
    SysKnob(name, s.revSend[i], pct(s.revSend[i]), Defaults.revSend[i]) { v ->
        SysReverbUi.update(c) { it.copy(revSend = setBand(it.revSend, i, v)) }
    }
}

private fun echoSendKnobs(c: Context, s: SysReverbSettings): List<SysKnob> = BandNames.mapIndexed { i, name ->
    SysKnob(name, s.echoSend[i], pct(s.echoSend[i]), Defaults.echoSend[i]) { v ->
        SysReverbUi.update(c) { it.copy(echoSend = setBand(it.echoSend, i, v)) }
    }
}

/** Kartu knob: sama dengan MixGroupCard (judul + saklar opsional, kotak knob selebar colW, perRow kotak per baris). */
@Composable
private fun SysKnobCard(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    dim: Boolean,
    knobs: List<SysKnob>,
    perRow: Int,
    opacity: Float,
    colW: Dp,
    modifier: Modifier
) {
    GroupCard(title, on, onToggle, opacity, modifier) {
        for (row in knobs.chunked(perRow)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)
            ) {
                for (k in row) {
                    KnobTile(
                        k.label, k.value, k.display, opacity, Modifier.width(colW).fillMaxHeight(),
                        dim = dim,
                        onReset = { k.onChange(k.reset) },
                        onChange = k.onChange
                    )
                }
            }
        }
    }
}

/** Kartu OUTPUT: knob GAIN + meter keluaran efek. meterFills: true = meter mengisi sisa lebar (susunan tegak), false = kotak selebar colW (susunan lebar). */
@Composable
private fun SysOutputCard(
    c: Context, s: SysReverbSettings, dim: Boolean, level: () -> Float, opacity: Float, colW: Dp,
    modifier: Modifier, meterFills: Boolean
) {
    GroupCard("OUTPUT", false, null, opacity, modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)
        ) {
            KnobTile(
                "GAIN", s.gain, "${(s.gain * 200).roundToInt()}%", opacity, Modifier.width(colW).fillMaxHeight(),
                dim = dim,
                onReset = { SysReverbUi.update(c) { it.copy(gain = Defaults.gain) } }
            ) { v -> SysReverbUi.update(c) { it.copy(gain = v) } }
            MeterTile("OUT", level, opacity, (if (meterFills) Modifier.weight(1f) else Modifier.width(colW)).fillMaxHeight())
        }
    }
}

@Composable
private fun rememberSysLevel(running: Boolean): () -> Float {
    var level by remember { mutableStateOf(0f) }
    LaunchedEffect(running) {
        while (running) {
            val amp = sqrt(YtReverb.meanSquare)
            level = ((20f * log10(amp + 1e-6f) + 60f) / 60f).coerceIn(0f, 1f)
            delay(33)
        }
        level = 0f
    }
    return { level }
}

/**
 * Pita REVERB AUDIO untuk susunan LEBAR. Semua kotak selebar colW (sama dengan bagian MIKROFON di atasnya), setinggi KNOB_ROW_DP.
 * Baris 1: REVERB (6) + DELAY (3). Baris 2: KIRIM KE REVERB (4) + KIRIM KE DELAY (4) + OUTPUT (2).
 */
@Composable
fun SysReverbWide(opacity: Float, onToggle: () -> Unit, colW: Dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current.applicationContext
    remember { SysReverbUi.ensureLoaded(c); true }
    val s = SysReverbUi.s
    val running by YtReverb.running.collectAsState()
    val level = rememberSysLevel(running)
    val cardH = cardHeight(1)
    MixSection("REVERB AUDIO", running, onToggle, opacity, modifier, fill = false) {
        Row(Modifier.height(cardH), horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
            SysKnobCard(
                "REVERB", s.reverbOn, { SysReverbUi.update(c) { it.copy(reverbOn = !it.reverbOn) } },
                !(running && s.reverbOn), reverbKnobs(c, s), 6, opacity, colW, Modifier.width(cardWidth(6, colW)).fillMaxHeight()
            )
            SysKnobCard(
                "DELAY", s.echoOn, { SysReverbUi.update(c) { it.copy(echoOn = !it.echoOn) } },
                !(running && s.echoOn), delayKnobs(c, s), 3, opacity, colW, Modifier.width(cardWidth(3, colW)).fillMaxHeight()
            )
        }
        Row(Modifier.height(cardH), horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
            SysKnobCard(
                "KIRIM KE REVERB", false, null, !running, reverbSendKnobs(c, s), 4, opacity, colW,
                Modifier.width(cardWidth(4, colW)).fillMaxHeight()
            )
            SysKnobCard(
                "KIRIM KE DELAY", false, null, !running, echoSendKnobs(c, s), 4, opacity, colW,
                Modifier.width(cardWidth(4, colW)).fillMaxHeight()
            )
            SysOutputCard(c, s, !running, level, opacity, colW, Modifier.width(cardWidth(2, colW)).fillMaxHeight(), false)
        }
    }
}

/** Bagian REVERB AUDIO untuk susunan TEGAK: kartu bertumpuk, 4 kotak selebar colW per baris, tinggi mengikuti isi. */
@Composable
fun SysReverbNarrow(opacity: Float, onToggle: () -> Unit, colW: Dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current.applicationContext
    remember { SysReverbUi.ensureLoaded(c); true }
    val s = SysReverbUi.s
    val running by YtReverb.running.collectAsState()
    val level = rememberSysLevel(running)
    MixSection("REVERB AUDIO", running, onToggle, opacity, modifier, fill = false) {
        SysKnobCard(
            "REVERB", s.reverbOn, { SysReverbUi.update(c) { it.copy(reverbOn = !it.reverbOn) } },
            !(running && s.reverbOn), reverbKnobs(c, s), 4, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(2))
        )
        SysKnobCard(
            "DELAY", s.echoOn, { SysReverbUi.update(c) { it.copy(echoOn = !it.echoOn) } },
            !(running && s.echoOn), delayKnobs(c, s), 4, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysKnobCard(
            "KIRIM KE REVERB", false, null, !running, reverbSendKnobs(c, s), 4, opacity, colW,
            Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysKnobCard(
            "KIRIM KE DELAY", false, null, !running, echoSendKnobs(c, s), 4, opacity, colW,
            Modifier.fillMaxWidth().height(cardHeight(1))
        )
        SysOutputCard(c, s, !running, level, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(1)), true)
    }
}
