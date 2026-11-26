package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinUiState

// ---------------------------------------------------------------------------------------------------------------------
// PANEL MIXER TUNGGAL. Layar utama DAN panel floating memanggil MixerGrid() yang sama -> kartu, urutan, warna, ukuran
// huruf, dan perilaku sentuh IDENTIK. Satu-satunya perbedaan: susunan menyesuaikan bentuk ruang yang tersedia
//   - LEBAR (layar utama landscape / floating dilebarkan): dua bagian berdampingan, semua muat tanpa gulir.
//   - TEGAK (floating sempit): dua bagian ditumpuk, 4 kotak per baris, digulir ke bawah.
// Bagian ketiga REVERB AUDIO (SysReverbPanel.kt) memakai komponen yang sama: pita di bawah (lebar) / kartu bertumpuk (tegak).
// Bagian keempat (mode equalizer) = EQUALIZER 31 BAND (EqualizerPanel.kt), selalu paling bawah, menyatu dalam gulir yang sama.
// Isi kartu diambil dari MixGroups (MainActivity.kt) -> menambah kontrol baru cukup di sana, kedua tampilan ikut.
//
// Peta kolom (satu kolom = satu kotak knob; SEMUA kotak di satu layar selebar colW dan setinggi KNOB_ROW_DP, knob berdiameter tetap):
//   LEBAR  (10 kolom) MIKROFON (8): [MIC 2][REVERB 6] / [ECHO 3][EQ MIC 3][LEVEL 2]    EQUALIZER AUDIO (2): [GAIN SUB] / [LOW MID] / [HIGH OUT]
//   TEGAK  (4 kolom)  MIKROFON: [MIC 2][LEVEL 2] / [REVERB 4+2] / [ECHO 3] / [EQ MIC 3]    EQUALIZER AUDIO: 4 + 2 (kotak tidak ditarik melebar)
// REVERB AUDIO hanya tampil di jendela biasa saat mode YouTube in app aktif (lihat sysReverbVisible).
// ---------------------------------------------------------------------------------------------------------------------

internal const val KNOB_ROW_DP = 84        // tinggi satu baris knob (SEMUA susunan: kotak knob selalu setinggi ini)
private const val WIDE_MIN_WIDTH_DP = 640 // lebih sempit dari ini -> susunan tegak

// Ukuran yang membentuk grid. Satu "kolom" = satu kotak knob selebar colW (dihitung dari lebar layar, SAMA untuk semua kartu).
internal const val TILE_GAP_DP = 4         // jarak antar kotak dalam satu kartu
internal const val CARD_PAD_DP = 4         // padding kartu (GroupCard)
internal const val CARD_GAP_DP = 6         // jarak antar kartu
internal const val SEC_PAD_DP = 5          // padding isi bagian (MixSection)
private const val SEC_HEADER_DP = 30
private const val WIDE_COLS = 10           // susunan lebar: MIKROFON 8 kolom + EQUALIZER AUDIO 2 kolom
private const val NARROW_COLS = 4          // susunan tegak: 4 kolom, kartu mengisi lebar penuh
// Lebar tetap di luar kolom (padding + jarak): dihitung dari rumus di bawah, +1 dp cadangan pembulatan.
private const val WIDE_FIXED_DP = 87f
private const val NARROW_FIXED_DP = 31f

private fun groupOf(toggleFn: String): MixGroup = MixGroups.first { it.toggleFn == toggleFn }

// tinggi kartu = padding 8 + judul 30 + jarak 4 + baris knob (+ jarak antar baris 4)
internal fun cardHeight(rows: Int): Dp = (8 + 30 + 4 + rows * KNOB_ROW_DP + (rows - 1) * 4).dp

/** Lebar kartu yang memuat n kotak selebar c. */
internal fun cardWidth(n: Int, c: Dp): Dp = c * n + (TILE_GAP_DP * (n - 1) + CARD_PAD_DP * 2).dp

/** Lebar satu kolom pada susunan lebar / tegak. */
internal fun wideColWidth(boxWidth: Dp): Dp = ((boxWidth.value - WIDE_FIXED_DP) / WIDE_COLS).coerceAtLeast(40f).dp
internal fun narrowColWidth(boxWidth: Dp): Dp = ((boxWidth.value - NARROW_FIXED_DP) / NARROW_COLS).coerceAtLeast(40f).dp

// Tinggi bagian pada susunan lebar: judul 30 + padding 10 + tiga baris knob (EQ AUDIO 2 kolom x 3 baris) + dua jarak 5.
private const val WIDE_SECTION_H_DP = SEC_HEADER_DP + SEC_PAD_DP * 2 + 3 * KNOB_ROW_DP + 2 * 5

@Composable
private fun MixKnob(k: MixControl, groupOn: Boolean, ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val v = ui.values[k.fn] ?: 0f
    KnobTile(
        k.label, v, formatMixValue(k.fn, v), opacity, modifier,
        bipolar = k.bipolar,
        dim = !groupOn,
        onReset = { bridge.resetValues(listOf(k.fn)) }
    ) { bridge.setValue(k.fn, it) }
}

/** Kartu satu kelompok dengan saklar ON/OFF sendiri. perRow = jumlah kotak per baris (lebih banyak dari itu -> baris baru). Semua kotak selebar colW. */
@Composable
private fun MixGroupCard(
    g: MixGroup,
    ui: SkinUiState,
    bridge: MixerBridge,
    opacity: Float,
    colW: Dp,
    modifier: Modifier,
    perRow: Int = g.controls.size
) {
    val on = ui.toggles[g.toggleFn] == true
    GroupCard(g.title, on, { bridge.setToggle(g.toggleFn, !on) }, opacity, modifier) {
        for (row in g.controls.chunked(perRow)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)
            ) {
                for (k in row) MixKnob(k, on, ui, bridge, opacity, Modifier.width(colW).fillMaxHeight())
            }
        }
    }
}

/** Kartu meter level mic (tanpa saklar). */
@Composable
private fun LevelCard(ui: SkinUiState, opacity: Float, modifier: Modifier) {
    GroupCard("LEVEL", false, null, opacity, modifier) {
        MeterTile("", { ui.meters["meter.mic"] ?: 0f }, opacity, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Isi bagian EQUALIZER AUDIO: knob + meter OUT (kotak terakhir) disusun cols kotak per baris, semua kotak selebar colW dan setinggi
 * KNOB_ROW_DP. Baris terakhir yang tidak penuh dibiarkan kosong (kotak tidak ditarik melebar).
 */
@Composable
private fun ColumnScope.EqAudioRows(ui: SkinUiState, bridge: MixerBridge, opacity: Float, cols: Int, colW: Dp) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    val knobs = audio.controls
    val total = knobs.size + 1
    for (r in 0 until (total + cols - 1) / cols) {
        Row(
            modifier = Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp, Alignment.CenterHorizontally)
        ) {
            for (col in 0 until cols) {
                val i = r * cols + col
                when {
                    i < knobs.size -> MixKnob(knobs[i], audioOn, ui, bridge, opacity, Modifier.width(colW).fillMaxHeight())
                    i == knobs.size -> MeterTile("OUT", { ui.meters["meter.output"] ?: 0f }, opacity, Modifier.width(colW).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun MixerWide(ui: SkinUiState, bridge: MixerBridge, opacity: Float, colW: Dp, modifier: Modifier) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    val cardH = cardHeight(1)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
        // MIKROFON: 8 kolom. Lebar = 8 kolom + jarak/padding kartu terlebar (baris 2: tiga kartu) + padding bagian.
        MixSection("MIKROFON", false, null, opacity, Modifier.width(colW * 8 + 66.dp).height(WIDE_SECTION_H_DP.dp)) {
            Row(Modifier.height(cardH), horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, colW, Modifier.width(cardWidth(2, colW)).fillMaxHeight())
                MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, colW, Modifier.width(cardWidth(6, colW)).fillMaxHeight())
            }
            Row(Modifier.height(cardH), horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
                MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, colW, Modifier.width(cardWidth(3, colW)).fillMaxHeight())
                MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, colW, Modifier.width(cardWidth(3, colW)).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.width(cardWidth(2, colW)).fillMaxHeight())
            }
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.width(colW * 2 + 14.dp).height(WIDE_SECTION_H_DP.dp)
        ) {
            EqAudioRows(ui, bridge, opacity, 2, colW)
        }
    }
}

@Composable
private fun MixerNarrow(
    ui: SkinUiState, bridge: MixerBridge, opacity: Float, onSysReverbToggle: () -> Unit, scroll: ScrollState, colW: Dp, modifier: Modifier,
    showStage: Boolean, stageEditable: Boolean, ytHost: String
) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    val sysVisible = sysReverbVisible(ytHost)
    Column(
        modifier = modifier.verticalScroll(scroll).ytAnchor(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // PANGGUNG + YOUTUBE (potret, di samping panggung). Jendela pemegang WebView ditentukan ytHost ("main" / "float").
        AnchoredStageAndYoutube(scroll, ytHost, Modifier.fillMaxWidth(), showStage, stageEditable)
        // Mode CHANNEL: strip per channel (fader + meter) di atas mixer klasik.
        if (ChUi.mode) ChannelSection(ui, bridge, opacity, onSysReverbToggle, Modifier.fillMaxWidth(), floating = ytHost == YtHost.FLOAT)
        MixSection("MIKROFON", false, null, opacity, Modifier.fillMaxWidth(), fill = false) {
            Row(Modifier.fillMaxWidth().height(cardHeight(1)), horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, colW, Modifier.width(cardWidth(2, colW)).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.weight(1f).fillMaxHeight())
            }
            MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(2)), perRow = NARROW_COLS)
            MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(1)), perRow = NARROW_COLS)
            MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, colW, Modifier.fillMaxWidth().height(cardHeight(1)), perRow = NARROW_COLS)
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.fillMaxWidth(), fill = false
        ) {
            EqAudioRows(ui, bridge, opacity, NARROW_COLS, colW)
        }
        // REVERB AUDIO hanya untuk pemutar YouTube di app: tidak tampil di floating dan tidak tampil bila mode YouTube mati.
        if (sysVisible) SysReverbNarrow(opacity, onSysReverbToggle, colW, Modifier.fillMaxWidth())
        // Mode equalizer: bagian EQUALIZER 31 BAND tepat di bawah (gulir ke bawah).
        if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
        if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
        if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
    }
}

/**
 * REVERB AUDIO (reverb + delay Web Audio) hidup di pemutar YouTube di dalam app, jadi bagiannya HANYA tampil di jendela biasa
 * (bukan floating) dan HANYA saat mode YouTube in app aktif.
 */
internal fun sysReverbVisible(ytHost: String): Boolean = ytHost != YtHost.FLOAT && YtUi.mode

/** Satu-satunya panel mixer: dipanggil layar utama (MainActivity) dan panel floating (FloatingBody). */
@Composable
fun MixerGrid(
    ui: SkinUiState,
    bridge: MixerBridge,
    hasBackgroundImage: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    onSysReverbToggle: () -> Unit = {},
    /** Dari pemanggil (layar utama) supaya tombol lompat ke EQUALIZER bisa menggulir panel ini. */
    scroll: ScrollState = rememberScrollState(),
    /** PANGGUNG (live stage) tampil di atas mixer bila modenya aktif. Panel floating mematikannya (false). */
    showStage: Boolean = true,
    /** false = panggung hanya tampil (floating). */
    stageEditable: Boolean = true,
    /** Jendela yang menampilkan YouTube: "main" (layar utama) atau "float" (panel floating). Panel floating WAJIB mengisi "float". */
    ytHost: String = YtHost.MAIN
) {
    val ctx = LocalContext.current
    remember { Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx); FxUi.ensureLoaded(ctx); StageUi.ensureLoaded(ctx); YtUi.ensureLoaded(ctx); ChUi.ensureLoaded(ctx) }
    BoxWithConstraints(
        modifier = modifier
            .background(if (hasBackgroundImage) Color.Transparent else PadLineColor)
            .padding(6.dp)
    ) {
        val boxMaxWidth = this.maxWidth
        val boxMaxHeight = this.maxHeight
        val wide = boxMaxWidth >= WIDE_MIN_WIDTH_DP.dp && boxMaxWidth >= boxMaxHeight * 1.3f
        if (!wide) {
            MixerNarrow(ui, bridge, opacity, onSysReverbToggle, scroll, narrowColWidth(boxMaxWidth), Modifier.fillMaxSize(), showStage, stageEditable, ytHost)
        } else {
            val colW = wideColWidth(boxMaxWidth)
            // MIKROFON + EQUALIZER AUDIO mengisi layar; pita REVERB AUDIO tepat di bawahnya (gulir ke bawah),
            // lalu (mode equalizer) bagian EQUALIZER 31 BAND.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).ytAnchor(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnchoredStageAndYoutube(scroll, ytHost, Modifier.fillMaxWidth(), showStage, stageEditable)
                if (ChUi.mode) ChannelSection(ui, bridge, opacity, onSysReverbToggle, Modifier.fillMaxWidth(), floating = ytHost == YtHost.FLOAT)
                MixerWide(ui, bridge, opacity, colW, Modifier)
                if (sysReverbVisible(ytHost)) SysReverbWide(opacity, onSysReverbToggle, colW, Modifier.fillMaxWidth())
                if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
                if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
                if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
            }
        }
    }
}
