// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.recognition.DetectorKind
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.RecognitionResult
import de.fabianduerkop.cardindex.recognition.numberKey
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.theme.TabularFigures
import java.util.Locale

private val CardShape = RoundedCornerShape(4.dp)
private val Tabular = TextStyle(fontFeatureSettings = TabularFigures)

/**
 * The identified card in one compact row (crop, collector number, name, set, scores). [details] adds the top 5,
 * the number OCR and the timings. [outdated]: the live card is from an earlier frame, so it is dimmed.
 */
@Composable
fun ResultPanel(
    result: RecognitionResult?,
    identified: Boolean,
    stable: Boolean,
    outdated: Boolean,
    details: Boolean,
    onToggleDetails: () -> Unit,
    message: String?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (result == null) {
                Text(
                    stringResource(R.string.scan_lab_hint),
                    Modifier.weight(1f).padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BestMatch(result, identified, stable, Modifier.weight(1f).alpha(if (outdated || !identified) 0.45f else 1f))
            }
            TextButton(onClick = onToggleDetails) {
                Text(stringResource(if (details) R.string.scan_lab_less else R.string.scan_lab_details))
            }
        }
        if (details && result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.reranked.forEachIndexed { i, m -> Candidate(m, highlighted = i == 0, Modifier.weight(1f)) }
            }
            Text(ocrLine(result), style = MaterialTheme.typography.labelMedium.merge(Tabular))
            Text(
                timingLine(result),
                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BestMatch(result: RecognitionResult, identified: Boolean, stable: Boolean, modifier: Modifier) {
    val best = result.best
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            result.crop.asImageBitmap(),
            contentDescription = stringResource(R.string.scan_lab_crop),
            modifier = Modifier.height(96.dp).aspectRatio(63f / 88f).clip(CardShape),
            contentScale = ContentScale.FillBounds,
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            CollectorNumber(best.card.localId, best.card.official, style = MaterialTheme.typography.headlineLarge)
            Text(best.card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    if (best.card.unseen) R.string.scan_lab_set_unseen else R.string.scan_lab_set,
                    best.card.setName,
                    best.card.lang.uppercase(Locale.ROOT),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val state = when {
                !identified -> R.string.scan_lab_below_min
                stable -> R.string.scan_lab_stable
                else -> R.string.scan_lab_identified
            }
            Text(
                stringResource(R.string.scan_lab_scores, best.similarity, result.margin, stringResource(state)),
                style = MaterialTheme.typography.labelMedium.merge(Tabular),
            )
        }
    }
}

/** Lab switches and thresholds, under "Details". */
@Composable
fun LabSettings(viewModel: ScanViewModel) {
    val readNumber by viewModel.readNumber.collectAsStateWithLifecycle()
    val saveCaptures by viewModel.saveCaptures.collectAsStateWithLifecycle()
    val minScore by viewModel.minScore.collectAsStateWithLifecycle()
    val minSimilarity by viewModel.minSimilarity.collectAsStateWithLifecycle()
    val detector by viewModel.detector.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val learnedAvailable = (engine as? EngineState.Ready)?.learnedDetector == true
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_lab_detector), style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = detector == DetectorKind.LEARNED, enabled = learnedAvailable,
                onClick = { viewModel.setDetector(DetectorKind.LEARNED) },
                label = { Text(stringResource(R.string.scan_lab_detector_learned)) })
            FilterChip(selected = detector == DetectorKind.CLASSIC, onClick = { viewModel.setDetector(DetectorKind.CLASSIC) },
                label = { Text(stringResource(R.string.scan_lab_detector_classic)) })
        }
        val languages by viewModel.languages.collectAsStateWithLifecycle()
        val available by viewModel.availableLanguages.collectAsStateWithLifecycle()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_lab_languages), style = MaterialTheme.typography.labelMedium)
            available.forEach { lang ->
                FilterChip(selected = lang in languages, onClick = { viewModel.toggleLanguage(lang) },
                    label = { Text(lang.uppercase(Locale.ROOT)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = readNumber, onClick = { viewModel.setReadNumber(!readNumber) },
                label = { Text(stringResource(R.string.scan_lab_ocr)) })
            FilterChip(selected = saveCaptures, onClick = { viewModel.setSaveCaptures(!saveCaptures) },
                label = { Text(stringResource(R.string.scan_lab_save)) })
        }
        LabeledSlider(stringResource(R.string.scan_lab_min_similarity, minSimilarity), minSimilarity, viewModel::setMinSimilarity,
            0.2f..0.9f, steps = 13)
        if (detector == DetectorKind.LEARNED) {
            LabeledSlider(stringResource(R.string.scan_lab_min_score, minScore), minScore, viewModel::setMinScore,
                0f..0.3f, steps = 11)
        } else {
            LabeledSlider(stringResource(R.string.scan_lab_min_score, minScore), minScore, viewModel::setMinScore,
                0f..0.7f, steps = 13)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium.merge(Tabular))
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Candidate(match: Match, highlighted: Boolean, modifier: Modifier) {
    Column(modifier) {
        AsyncImage(
            model = match.card.thumbnailUrl,
            contentDescription = match.card.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(63f / 88f)
                .clip(CardShape)
                .then(
                    if (highlighted) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CardShape)
                    else Modifier,
                ),
            contentScale = ContentScale.Fit,
        )
        Text(
            "${match.card.lang.uppercase(Locale.ROOT)} ${match.card.localId}",
            style = MaterialTheme.typography.labelSmall.merge(Tabular),
            maxLines = 1,
        )
        Text(
            String.format(Locale.ROOT, "%.3f", match.similarity),
            style = MaterialTheme.typography.labelSmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ocrLine(r: RecognitionResult): String {
    val reading = r.reading ?: return stringResource(R.string.scan_lab_ocr_none, r.ocrText ?: "–")
    val read = reading.total?.let { "${reading.number}/$it" } ?: reading.number
    val agrees = numberKey(r.candidates[0].card.localId) == reading.number
    return stringResource(if (agrees) R.string.scan_lab_ocr_agrees else R.string.scan_lab_ocr_differs, read)
}

@Composable
private fun timingLine(r: RecognitionResult): String {
    val t = r.timings
    val outline = if (r.found) stringResource(R.string.scan_lab_outline_found, r.detectionScore)
    else stringResource(R.string.scan_lab_outline_fallback)
    val flipped = if (r.flipped) stringResource(R.string.scan_lab_flipped) else ""
    return stringResource(R.string.scan_lab_timings, t.detect, t.embed, t.knn, t.ocr, t.total, r.frameWidth, r.frameHeight) +
        " · $outline$flipped"
}
