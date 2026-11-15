package com.dearded.spektralab

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved border combination: the preset, the paper frame the user tuned
 * (margins, paper, logo, caption, signature, perforations) and the film edge
 * and date back settings. Per-photo choices (crop, exposure, white balance,
 * the date itself) stay with the photo, exactly as [Preset] leaves them out.
 *
 * Saving the whole combination is what lets 边宽 · 相纸 · 片边 · 日期 be
 * restored in one tap; picking a border preset alone cannot.
 */
data class BorderFavorite(
    val name: String,
    val borderId: String,
    val frame: FrameSpec,
    val filmEdge: Boolean,
    val edgeFormat: String,
    val edgeMode: String,
    val edgeHoles: String,
    val edgeGate: String,
    val edgeCarrier: String,
    val edgeFog: Double,
    val edgeLeaks: Double,
    val edgeRealName: Boolean,
    val edgeFrameNumber: Int,
    /** The gate centring, weave and leak positions; restoring them restores the same strip. */
    val edgeCameraSeed: Int,
    val edgeFrameSeed: Int,
    val dateImprint: Boolean,
    val dateStyle: String,
    val dateCorner: String,
    val dateOrder: String,
    val dateWithData: Boolean,
    val datePlacement: String,
    val dateSize: Double,
    val dateEv: Double,
    val dateInsetX: Double,
    val dateInsetY: Double,
) {
    /** The current photo with this combination: its gate/card crop, then the saved border settings. */
    fun applyTo(edit: Edit, w: Int, h: Int): Edit {
        val preset = Borders.byId(borderId)
        // Start from the preset so an instant card or a film gate still crops to
        // this photo's shape; then put the saved tuning and edge/date settings back.
        var next = if (preset != null) Edit.withBorder(edit, preset, w, h) else edit.copy(borderId = borderId)
        next = next.copy(
            borderId = borderId,
            frame = frame,
            edgeFormat = edgeFormat,
            edgeMode = edgeMode,
            edgeHoles = edgeHoles,
            edgeGate = edgeGate,
            edgeCarrier = edgeCarrier,
            edgeFog = edgeFog,
            edgeLeaks = edgeLeaks,
            edgeRealName = edgeRealName,
            edgeFrameNumber = edgeFrameNumber.coerceIn(0, 99),
            edgeCameraSeed = edgeCameraSeed,
            edgeFrameSeed = edgeFrameSeed,
            dateStyle = if (!filmEdge && dateStyle == "data") "lcd" else dateStyle,
            dateCorner = dateCorner,
            dateOrder = dateOrder,
            dateWithData = dateWithData,
            datePlacement = if (filmEdge && Edit.dateBackFits(edgeFormat)) datePlacement else "frame",
            dateSize = dateSize.coerceIn(0.4, 3.0),
            dateEv = dateEv.coerceIn(-2.0, 8.0),
            dateInsetX = dateInsetX.coerceIn(0.0, 30.0),
            dateInsetY = dateInsetY.coerceIn(0.0, 30.0),
        )
        // The gate's crop and format belong to the film edge switch, not the preset.
        next = Edit.withFilmEdge(next, w, h, filmEdge, edgeFormat)
        // A date back only prints when this photo has a film, a date and a gauge with a back.
        return next.copy(dateImprint = dateImprint && !next.noFilm && next.dateText.isNotBlank() &&
            (!filmEdge || Edit.dateBackFits(edgeFormat)))
    }

    /** Whether [edit] already is this combination, for the selected chip. */
    fun matches(edit: Edit): Boolean {
        val dateOn = dateImprint && !edit.noFilm && edit.dateText.isNotBlank() && (!filmEdge || Edit.dateBackFits(edgeFormat))
        return edit.borderId == borderId && edit.frame == frame && edit.filmEdge == filmEdge &&
            edit.edgeFormat == edgeFormat && edit.edgeMode == edgeMode && edit.edgeHoles == edgeHoles &&
            edit.edgeGate == edgeGate && edit.edgeCarrier == edgeCarrier && edit.edgeFog == edgeFog &&
            edit.edgeLeaks == edgeLeaks && edit.edgeRealName == edgeRealName &&
            edit.edgeFrameNumber == edgeFrameNumber.coerceIn(0, 99) &&
            edit.edgeCameraSeed == edgeCameraSeed && edit.edgeFrameSeed == edgeFrameSeed &&
            edit.dateImprint == dateOn && edit.dateStyle == (if (!filmEdge && dateStyle == "data") "lcd" else dateStyle) &&
            edit.dateCorner == dateCorner && edit.dateOrder == dateOrder && edit.dateWithData == dateWithData &&
            edit.datePlacement == (if (filmEdge && Edit.dateBackFits(edgeFormat)) datePlacement else "frame") &&
            edit.dateSize == dateSize.coerceIn(0.4, 3.0) && edit.dateEv == dateEv.coerceIn(-2.0, 8.0) &&
            edit.dateInsetX == dateInsetX.coerceIn(0.0, 30.0) && edit.dateInsetY == dateInsetY.coerceIn(0.0, 30.0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("borderId", borderId).put("frame", frame.toJson())
        .put("filmEdge", filmEdge).put("edgeFormat", edgeFormat).put("edgeMode", edgeMode)
        .put("edgeHoles", edgeHoles).put("edgeGate", edgeGate).put("edgeCarrier", edgeCarrier)
        .put("edgeFog", edgeFog).put("edgeLeaks", edgeLeaks).put("edgeRealName", edgeRealName)
        .put("edgeFrameNumber", edgeFrameNumber).put("edgeCameraSeed", edgeCameraSeed)
        .put("edgeFrameSeed", edgeFrameSeed).put("dateImprint", dateImprint).put("dateStyle", dateStyle)
        .put("dateCorner", dateCorner).put("dateOrder", dateOrder).put("dateWithData", dateWithData)
        .put("datePlacement", datePlacement).put("dateSize", dateSize).put("dateEv", dateEv)
        .put("dateInsetX", dateInsetX).put("dateInsetY", dateInsetY)

    companion object {
        /** [name] over the border part of [edit]; nothing per-photo is kept. */
        fun of(name: String, edit: Edit) = BorderFavorite(
            name = name,
            borderId = edit.borderId,
            frame = edit.frame,
            filmEdge = edit.filmEdge,
            edgeFormat = edit.edgeFormat,
            edgeMode = edit.edgeMode,
            edgeHoles = edit.edgeHoles,
            edgeGate = edit.edgeGate,
            edgeCarrier = edit.edgeCarrier,
            edgeFog = edit.edgeFog,
            edgeLeaks = edit.edgeLeaks,
            edgeRealName = edit.edgeRealName,
            edgeFrameNumber = edit.edgeFrameNumber.coerceIn(0, 99),
            edgeCameraSeed = edit.edgeCameraSeed,
            edgeFrameSeed = edit.edgeFrameSeed,
            dateImprint = edit.dateImprint,
            dateStyle = edit.dateStyle,
            dateCorner = edit.dateCorner,
            dateOrder = edit.dateOrder,
            dateWithData = edit.dateWithData,
            datePlacement = edit.datePlacement,
            dateSize = edit.dateSize,
            dateEv = edit.dateEv,
            dateInsetX = edit.dateInsetX,
            dateInsetY = edit.dateInsetY,
        )

        /** Missing keys take the defaults of a fresh [Edit], so older saves keep loading. */
        fun fromJson(o: JSONObject): BorderFavorite? {
            val d = Edit()
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
            return BorderFavorite(
                name = name.take(40),
                borderId = o.optString("borderId", d.borderId),
                frame = o.optJSONObject("frame")?.let { FrameSpec.fromJson(it) } ?: FrameSpec(),
                filmEdge = o.optBoolean("filmEdge", d.filmEdge),
                edgeFormat = o.optString("edgeFormat", d.edgeFormat),
                edgeMode = o.optString("edgeMode", d.edgeMode),
                edgeHoles = o.optString("edgeHoles", d.edgeHoles),
                edgeGate = o.optString("edgeGate", d.edgeGate),
                edgeCarrier = o.optString("edgeCarrier", d.edgeCarrier),
                edgeFog = o.optDouble("edgeFog", d.edgeFog),
                edgeLeaks = o.optDouble("edgeLeaks", d.edgeLeaks),
                edgeRealName = o.optBoolean("edgeRealName", d.edgeRealName),
                edgeFrameNumber = o.optInt("edgeFrameNumber", d.edgeFrameNumber).coerceIn(0, 99),
                edgeCameraSeed = o.optInt("edgeCameraSeed", d.edgeCameraSeed),
                edgeFrameSeed = o.optInt("edgeFrameSeed", d.edgeFrameSeed),
                dateImprint = o.optBoolean("dateImprint", d.dateImprint),
                dateStyle = o.optString("dateStyle", d.dateStyle),
                dateCorner = o.optString("dateCorner", d.dateCorner),
                dateOrder = o.optString("dateOrder", d.dateOrder),
                dateWithData = o.optBoolean("dateWithData", d.dateWithData),
                datePlacement = o.optString("datePlacement", d.datePlacement),
                dateSize = o.optDouble("dateSize", d.dateSize),
                dateEv = o.optDouble("dateEv", d.dateEv),
                dateInsetX = o.optDouble("dateInsetX", d.dateInsetX),
                dateInsetY = o.optDouble("dateInsetY", d.dateInsetY),
            )
        }
    }
}

/** Saved border combinations in SharedPreferences, as one JSON array, in the order saved. */
class BorderFavoriteStore(private val prefs: SharedPreferences) {
    fun load(): List<BorderFavorite> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { o -> BorderFavorite.fromJson(o) } }
    }.getOrDefault(emptyList())

    /** Saving under an existing name replaces that combination in place. */
    fun save(favorite: BorderFavorite): List<BorderFavorite> {
        val list = load().toMutableList()
        val at = list.indexOfFirst { it.name == favorite.name }
        if (at >= 0) list[at] = favorite else list += favorite
        return write(list)
    }

    fun delete(name: String): List<BorderFavorite> = write(load().filterNot { it.name == name })

    private fun write(list: List<BorderFavorite>): List<BorderFavorite> {
        prefs.edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
        return list
    }

    private companion object { const val KEY = "border_favorites" }
}

/**
 * 常用边框 above the style picker: one tap puts back a saved combination, and
 * the open photo's border can be kept under a name. Saved combinations stay
 * reachable while editing every photo, like the film and paper presets.
 */
@Composable
internal fun BorderFavoriteRow(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadBorderFavorites() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TonalButton("存为常用边框", enabled = enabled) { naming = true }
        if (ui.borderFavorites.isNotEmpty()) {
            Box {
                TonalButton("管理（${ui.borderFavorites.size}）", enabled = enabled) { managing = true }
                DropdownMenu(expanded = managing, onDismissRequest = { managing = false }) {
                    for (f in ui.borderFavorites) {
                        DropdownMenuItem(
                            text = { Text(f.name) },
                            onClick = { managing = false; deleting = f.name },
                            trailingIcon = { Text("删除") },
                        )
                    }
                }
            }
        }
    }
    if (ui.borderFavorites.isEmpty()) {
        Hint("把边框、边宽、相纸、片边和日期调好后存起来，下一张照片一点就还原。")
    } else {
        PillRow {
            for (f in ui.borderFavorites) {
                SelectPill(selected = f.matches(ui.edit), enabled = enabled,
                    onClick = { vm.applyBorderFavorite(f) }, label = { Text(f.name) })
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            confirmButton = {
                TextButton(onClick = { naming = false; vm.saveBorderFavorite(name) }, enabled = name.isNotBlank()) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } },
            title = { Text("存为常用边框") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("名称") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "保存边框款式、边宽、相纸颜色、参数文字、片边和日期设置；裁剪、曝光和白平衡属于单张照片，不保存。同名会覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteBorderFavorite(target) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            title = { Text("删除常用边框") },
            text = { Text("删除“$target”？") },
        )
    }
}
