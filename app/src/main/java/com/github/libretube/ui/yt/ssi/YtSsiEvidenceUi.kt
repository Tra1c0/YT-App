package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.rememberYtHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * YT (SSI-3): the state of the evidence views (beside [YtSsiUi]): the open view, the hits chosen
 * for the comparison, the claim the relations are checked against (empty: the one read from the
 * query), the person the timeline follows, the notes and the saved research being worked on.
 */
object YtSsiEvidenceState {
    var view by mutableStateOf(YtSsiView.HITS)
    val chosen = mutableStateListOf<String>()
    var claim by mutableStateOf("")
    var person by mutableStateOf<String?>(null)
    var notes by mutableStateOf("")
    var researchId by mutableStateOf<String?>(null)
    var researchCreated = 0L

    /** The search the routing was done for (a search routes once). */
    var routedFor = 0L

    /** The search whose relations were started by its intent (once). */
    var autoFor = 0L

    /** A search whose relations start when it is done although its intent asks nothing (a continued research). */
    var forceAutoFor = 0L

    /** A new search: everything but a research that is being continued goes. */
    fun newSearch(keepResearch: Boolean) {
        view = YtSsiView.HITS
        chosen.clear()
        claim = ""
        person = null
        if (!keepResearch) {
            notes = ""
            researchId = null
            researchCreated = 0L
        }
    }

    fun toggle(key: String): Boolean {
        if (key in chosen) {
            chosen.remove(key)
            return true
        }
        if (chosen.size >= YtSsiRoute.MAX_COMPARE) return false
        chosen += key
        return true
    }

    /** The claim in effect for [intent]: the edited one or the query's. */
    fun claimOf(intent: YtSsiIntent?): String = claim.trim().ifEmpty { intent?.let(YtSsiStanceRules::claim).orEmpty() }
}

/** YT (SSI-3): the SAF launchers of the report export, registered by [YtSsiLayer.attach]. */
internal object YtSsiExport {
    var markdown: ActivityResultLauncher<String>? = null
    var json: ActivityResultLauncher<String>? = null
    private var pending: String? = null

    fun launch(context: Context, launcher: ActivityResultLauncher<String>?, name: String, content: String) {
        if (launcher == null) {
            Toast.makeText(context, R.string.yt_r37_ssi_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pending = content
        runCatching { launcher.launch(name) }.onFailure {
            pending = null
            Toast.makeText(context, R.string.yt_r37_ssi_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Writes the pending report to [uri] (the picker's answer). */
    fun write(context: Context, uri: Uri?) {
        val content = pending ?: return
        pending = null
        if (uri == null) return
        MainScope().launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } != null }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) R.string.yt_r37_ssi_export_done else R.string.yt_r37_ssi_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun date(ms: Long?): String? = ms?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

// ---- the parts every view uses ----

@Composable
internal fun ViewTabs(palette: YtPalette, back: Int, ink: YtInk, accentInk: YtInk) {
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (view in YtSsiView.entries) {
            val on = YtSsiEvidenceState.view == view
            val label = stringResource(
                when (view) {
                    YtSsiView.HITS -> R.string.yt_r37_ssi_view_hits
                    YtSsiView.EVIDENCE -> R.string.yt_r37_ssi_view_evidence
                    YtSsiView.COMPARE -> R.string.yt_r37_ssi_view_compare
                    YtSsiView.TIMELINE -> R.string.yt_r37_ssi_view_timeline
                    YtSsiView.REPORT -> R.string.yt_r37_ssi_view_report
                    YtSsiView.SAVED -> R.string.yt_r37_ssi_view_saved
                },
            )
            Text(
                label,
                color = if (on) accentInk.text else ink.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) palette.accent else Color(back))
                    .clickable(role = Role.Tab) {
                        if (!on) {
                            haptics.tick()
                            YtSsiEvidenceState.view = view
                        }
                    }
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The relation's name as the screen says it (contradiction and refutation apart). */
@Composable
internal fun relationLabel(stance: YtSsiStance): String = stringResource(
    when (YtSsiReport.group(stance)) {
        YtSsiGroup.SUPPORTS -> R.string.yt_r37_ssi_rel_supports
        YtSsiGroup.REFUTES_REASONED -> R.string.yt_r37_ssi_rel_refutes_reasoned
        YtSsiGroup.CONTRADICTS -> R.string.yt_r37_ssi_rel_contradicts
        YtSsiGroup.QUALIFIES -> R.string.yt_r37_ssi_rel_qualifies
        YtSsiGroup.QUOTES -> R.string.yt_r37_ssi_rel_quotes
        YtSsiGroup.DISCUSSES -> R.string.yt_r37_ssi_rel_discusses
        YtSsiGroup.UNCERTAIN, YtSsiGroup.UNCHECKED -> R.string.yt_r37_ssi_rel_uncertain
    },
)

@Composable
private fun groupLabel(group: YtSsiGroup): String = stringResource(
    when (group) {
        YtSsiGroup.SUPPORTS -> R.string.yt_r37_ssi_group_supports
        YtSsiGroup.REFUTES_REASONED -> R.string.yt_r37_ssi_group_refutes_reasoned
        YtSsiGroup.CONTRADICTS -> R.string.yt_r37_ssi_group_contradicts
        YtSsiGroup.QUALIFIES -> R.string.yt_r37_ssi_group_qualifies
        YtSsiGroup.QUOTES -> R.string.yt_r37_ssi_group_quotes
        YtSsiGroup.DISCUSSES -> R.string.yt_r37_ssi_group_discusses
        YtSsiGroup.UNCERTAIN -> R.string.yt_r37_ssi_group_uncertain
        YtSsiGroup.UNCHECKED -> R.string.yt_r37_ssi_group_unchecked
    },
)

@Composable
private fun flagLabel(flag: YtSsiContextFlag): String = stringResource(
    when (flag) {
        YtSsiContextFlag.IRONIC -> R.string.yt_r37_ssi_flag_ironic
        YtSsiContextFlag.HYPOTHETICAL -> R.string.yt_r37_ssi_flag_hypothetical
        YtSsiContextFlag.QUOTING_OTHER -> R.string.yt_r37_ssi_flag_quoting
        YtSsiContextFlag.CRITICAL -> R.string.yt_r37_ssi_flag_critical
    },
)

@Composable
private fun claimedLabel(relation: YtSsiRelation): String = stringResource(
    when (relation) {
        YtSsiRelation.SUPPORTS -> R.string.yt_r37_ssi_rel_supports
        YtSsiRelation.REFUTES -> R.string.yt_r37_ssi_rel_contradicts
        YtSsiRelation.QUALIFIES -> R.string.yt_r37_ssi_rel_qualifies
        YtSsiRelation.QUOTES -> R.string.yt_r37_ssi_rel_quotes
        YtSsiRelation.DISCUSSES -> R.string.yt_r37_ssi_rel_discusses
        YtSsiRelation.UNCERTAIN -> R.string.yt_r37_ssi_rel_uncertain
    },
)

/** Plays [hit] a moment before [ms] (a quote's second). */
private fun playAt(context: Context, hit: YtSsiHit, ms: Long) {
    ssiPlayFrom(context, hit, (ms - YtSsiPlayRules.LEAD_MS).coerceAtLeast(0))
}

/**
 * A hit's relation: its name, the verified quote (tap plays it), for a refutation the reason,
 * the context flags; "nicht belegt" with what the AI claimed when the quote was not found.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StanceBlock(hit: YtSsiHit, stance: YtSsiStance?, pending: Boolean, ink: YtInk) {
    val context = LocalContext.current
    if (pending) {
        Text(stringResource(R.string.yt_r37_ssi_stance_pending), color = ink.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        return
    }
    stance ?: return
    Column(Modifier.padding(top = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Badge(relationLabel(stance), ink, strong = true)
            if (!stance.verified) Badge(stringResource(R.string.yt_r37_ssi_not_evidenced), ink, strong = true)
            if (stance.cached) Badge(stringResource(R.string.yt_r37_ssi_cached), ink)
        }
        val evidence = stance.evidence
        if (evidence != null) {
            Text(
                "„" + evidence.quote + "“  " + YtSsiPlayRules.stamp(evidence.ms, evidence.timing),
                color = ink.text,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button) { playAt(context, hit, evidence.ms) },
            )
        } else {
            Text(
                when (stance.unsure) {
                    YtSsiUnsure.QUOTE_NOT_FOUND -> stringResource(R.string.yt_r37_ssi_unsure_not_found, claimedLabel(stance.claimed))
                    YtSsiUnsure.NO_QUOTE -> stringResource(R.string.yt_r37_ssi_unsure_no_quote, claimedLabel(stance.claimed))
                    else -> stringResource(R.string.yt_r37_ssi_unsure_ai)
                },
                color = ink.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (stance.relation == YtSsiRelation.UNCERTAIN && stance.unsure == YtSsiUnsure.AI_SAID && evidence != null) {
            Text(stringResource(R.string.yt_r37_ssi_unsure_ai), color = ink.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        stance.reason?.let { reason ->
            Text(
                stringResource(R.string.yt_r37_ssi_reason, reason.quote) + "  " + YtSsiPlayRules.stamp(reason.ms, reason.timing),
                color = ink.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button) { playAt(context, hit, reason.ms) },
            )
        }
        if (stance.relation == YtSsiRelation.REFUTES && stance.opposition == YtSsiOpposition.CONTRADICTS) {
            Text(stringResource(R.string.yt_r37_ssi_contradicts_note), color = ink.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        for ((flag, ev) in stance.flags) {
            Text(flagLabel(flag) + ": „" + ev.quote + "“", color = ink.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Badge(text: String, ink: YtInk, strong: Boolean = false) {
    Text(
        text,
        color = if (strong) ink.text else ink.textSecondary,
        fontSize = 11.sp,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.border(1.dp, (if (strong) ink.text else ink.textSecondary).copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun Small(text: String, ink: YtInk, onClick: () -> Unit) {
    Text(
        text,
        color = ink.text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .border(1.dp, ink.text.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** "Vergleichen" on a card: adds or removes the hit from the comparison (at most 4). */
@Composable
internal fun ChooseButton(hit: YtSsiHit, ink: YtInk) {
    val context = LocalContext.current
    val haptics = rememberYtHaptics()
    val key = YtSsiUi.key(hit)
    val on = key in YtSsiEvidenceState.chosen
    Small(if (on) "✓ " + stringResource(R.string.yt_r37_ssi_choose) else stringResource(R.string.yt_r37_ssi_choose), ink) {
        if (YtSsiEvidenceState.toggle(key)) {
            haptics.tick()
        } else {
            haptics.reject()
            Toast.makeText(context, context.getString(R.string.yt_r37_ssi_choose_full, YtSsiRoute.MAX_COMPARE), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun cardModifier(back: Int, onClick: (() -> Unit)? = null): Modifier {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(back))
    return (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(14.dp)
}

@Composable
private fun HitHead(hit: YtSsiHit, ink: YtInk, palette: YtPalette, origin: YtSsiOrigin?, titles: Map<String, String>) {
    Text(hit.title, color = ink.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = palette.font)
    val meta = listOfNotNull(hit.channelName, date(hit.uploaded), hit.segmentId?.let { YtSsiPlayRules.stamp(hit.hitMs, hit.timing) }).joinToString(" · ")
    if (meta.isNotEmpty()) Text(meta, color = ink.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (origin != null && origin.size > 1) {
        val text = if (origin.key == hit.videoId) {
            stringResource(R.string.yt_r37_ssi_origin_first, origin.size - 1)
        } else {
            stringResource(R.string.yt_r37_ssi_origin_same, titles[origin.key] ?: origin.key)
        }
        Text(text, color = ink.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---- the views ----

/**
 * The content of the views other than the hits for search [run]; its relations and origin
 * clusters count only when they were made for it.
 */
@Composable
internal fun EvidenceViews(run: YtSsiRun, palette: YtPalette, back: Int, ink: YtInk, accentInk: YtInk, modifier: Modifier) {
    val context = LocalContext.current
    val stanceState by YtSsiStances.run.collectAsState()
    val mine = stanceState.runId == run.id
    val stances = if (mine) stanceState.stances else emptyMap()
    val hits = run.hits
    LaunchedEffect(run.id, hits.map { it.videoId }.distinct(), run.phase) {
        if (hits.isNotEmpty()) YtSsiStances.origins(run.id, hits)
    }
    val origins = if (mine && stanceState.originsFor == hits.map { it.videoId }.distinct()) stanceState.origins else emptyList()
    val titles = remember(hits) { hits.associate { it.videoId to it.title } }
    // the key store is read off the main thread; the switches alone decide until then
    val ai by produceState(YtSsi.stanceOn(), run.id, stanceState.phase) { value = withContext(Dispatchers.IO) { YtSsiStances.available() } }
    when (YtSsiEvidenceState.view) {
        YtSsiView.HITS -> Unit
        YtSsiView.EVIDENCE -> Evidence(run, stanceState.takeIf { mine }, stances, origins, titles, ai, palette, back, ink, accentInk, modifier)
        YtSsiView.COMPARE -> Compare(run, stances, stanceState.pending.takeIf { mine }.orEmpty(), origins, titles, ai, palette, back, ink, modifier)
        YtSsiView.TIMELINE -> Timeline(run, stances, origins, titles, ai, palette, back, ink, modifier)
        YtSsiView.REPORT -> Report(context, run, stances, origins, ai, palette, back, ink, accentInk, modifier)
        YtSsiView.SAVED -> Saved(run, stances, palette, back, ink, accentInk, modifier)
    }
}

/** "Einordnungen brauchen die KI": shown on every view that would show relations. */
@Composable
private fun NoAi(palette: YtPalette) {
    Text(
        stringResource(R.string.yt_r37_ssi_no_ai),
        color = palette.textSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Empty(palette: YtPalette, text: String) {
    Text(text, color = palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}

/** The claim field, start/stop, the analysis' state, the origin count and the neutrality note. */
@Composable
private fun AnalysisHead(run: YtSsiRun, stanceRun: YtSsiStanceRun?, origins: List<YtSsiOrigin>, ai: Boolean, palette: YtPalette, back: Int, ink: YtInk, accentInk: YtInk) {
    val context = LocalContext.current
    val haptics = rememberYtHaptics()
    Column(Modifier.padding(horizontal = 4.dp)) {
        if (!ai) {
            NoAi(palette)
        } else {
            Text(stringResource(R.string.yt_r37_ssi_claim), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            val placeholder = run.intent?.let(YtSsiStanceRules::claim).orEmpty()
            BasicTextField(
                value = YtSsiEvidenceState.claim,
                onValueChange = { YtSsiEvidenceState.claim = it },
                textStyle = TextStyle(color = ink.text, fontSize = 15.sp, fontFamily = palette.font),
                cursorBrush = SolidColor(palette.accent),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(back)).padding(12.dp),
                decorationBox = { inner ->
                    Box {
                        if (YtSsiEvidenceState.claim.isEmpty()) Text(placeholder, color = ink.textSecondary, fontSize = 15.sp, fontFamily = palette.font)
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                val running = stanceRun?.phase == YtSsiStancePhase.RUNNING
                Text(
                    stringResource(if (running) R.string.yt_r37_ssi_stance_stop else if (stanceRun?.stances.isNullOrEmpty()) R.string.yt_r37_ssi_stance_start else R.string.yt_r37_ssi_stance_again),
                    color = accentInk.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.accent)
                        .clickable(role = Role.Button) {
                            haptics.confirm()
                            if (running) {
                                YtSsiStances.cancel()
                            } else if (run.hits.none { it.segmentId != null }) {
                                Toast.makeText(context, R.string.yt_r37_ssi_stance_nothing, Toast.LENGTH_SHORT).show()
                            } else {
                                YtSsiStances.start(context, run.id, YtSsiEvidenceState.claimOf(run.intent), run.query, run.hits)
                            }
                        }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.yt_r37_ssi_budget_line, YtSsi.stancePerSearch() * YtSsiStanceRules.BATCH, YtSsi.stancePerDay()),
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            stanceRun?.let { s ->
                if (s.phase == YtSsiStancePhase.RUNNING) {
                    Text(stringResource(R.string.yt_r37_ssi_stance_running, s.stances.size, s.requests), color = palette.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                for (note in s.notes.sortedBy { it.ordinal }) {
                    val text = when (note) {
                        YtSsiStanceNote.NO_AI -> R.string.yt_r37_ssi_note_no_ai
                        YtSsiStanceNote.USED -> R.string.yt_r37_ssi_note_used
                        YtSsiStanceNote.CACHED -> R.string.yt_r37_ssi_note_cached
                        YtSsiStanceNote.FAILED -> R.string.yt_r37_ssi_note_failed
                        YtSsiStanceNote.BUDGET_SEARCH -> R.string.yt_r37_ssi_note_budget_search
                        YtSsiStanceNote.BUDGET_DAY -> R.string.yt_r37_ssi_note_budget_day
                        YtSsiStanceNote.NOTHING -> R.string.yt_r37_ssi_note_nothing
                    }
                    Text("· " + stringResource(text), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        val videos = run.hits.map { it.videoId }.distinct().size
        if (videos > 0 && origins.isNotEmpty()) {
            Text(stringResource(R.string.yt_r37_ssi_origins, videos, origins.size), color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
        Text(stringResource(R.string.yt_r37_ssi_neutral), color = palette.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Evidence(
    run: YtSsiRun,
    stanceRun: YtSsiStanceRun?,
    stances: Map<Long, YtSsiStance>,
    origins: List<YtSsiOrigin>,
    titles: Map<String, String>,
    ai: Boolean,
    palette: YtPalette,
    back: Int,
    ink: YtInk,
    accentInk: YtInk,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val byVideo = remember(origins) { YtSsiSources.byVideo(origins) }
    val passages = run.hits.filter { it.segmentId != null }
    val groups = remember(passages, stances) {
        YtSsiGroup.entries.mapNotNull { g -> passages.filter { YtSsiReport.group(stances[it.segmentId]) == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
    }
    val pending = stanceRun?.pending.orEmpty()
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        item { AnalysisHead(run, stanceRun, origins, ai, palette, back, ink, accentInk) }
        if (passages.isEmpty()) item { Empty(palette, stringResource(R.string.yt_r37_ssi_empty)) }
        for ((group, list) in groups) {
            item(key = "g:${group.name}") {
                Text(groupLabel(group) + " (" + list.size + ")", color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            items(list, key = { "e:" + YtSsiUi.key(it) }) { hit ->
                Column(cardModifier(back)) {
                    HitHead(hit, ink, palette, byVideo[hit.videoId], titles)
                    val stance = stances[hit.segmentId]
                    // without a verified quote the passage itself is shown, so the reader can judge
                    if (stance?.evidence == null) {
                        Text(hit.text, color = ink.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    StanceBlock(hit, stance, hit.segmentId in pending, ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Small(stringResource(R.string.yt_r37_ssi_play), ink) { ssiPlay(context, hit) }
                        ChooseButton(hit, ink)
                    }
                }
            }
        }
        val same = origins.filter { it.size > 1 }
        if (same.isNotEmpty()) {
            item(key = "origins") {
                Column(cardModifier(back)) {
                    Text(stringResource(R.string.yt_r37_ssi_origin_title), color = ink.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    for (o in same) {
                        Text(
                            stringResource(R.string.yt_r37_ssi_origin_row, o.size, o.videos.joinToString(" · ") { titles[it] ?: it }) +
                                if (o.reasons.isEmpty()) "" else " (" + o.reasons.joinToString(", ") { originReason(context, it) } + ")",
                            color = ink.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun originReason(context: Context, reason: YtSsiSameOrigin): String = context.getString(
    when (reason) {
        YtSsiSameOrigin.NEAR_DUPLICATE -> R.string.yt_r37_ssi_origin_text
        YtSsiSameOrigin.SAME_LINK -> R.string.yt_r37_ssi_origin_link
        YtSsiSameOrigin.LINKS_VIDEO -> R.string.yt_r37_ssi_origin_links_video
        YtSsiSameOrigin.REUPLOAD -> R.string.yt_r37_ssi_origin_reupload
    },
)

/** ±30 s of what is said around a hit (the passages that overlap the 30 s before and after). */
private data class Around(val before: String, val after: String)

private fun around(hit: YtSsiHit): Around? {
    val segments = runCatching { YtSsi.db.segments(hit.videoId) }.getOrNull() ?: return null
    val start = hit.span?.startMs ?: hit.startMs
    val end = hit.span?.endMs ?: hit.endMs
    val before = segments.filter { it.kind == YtSsiSegmentKind.SPEECH && it.endMs > start - YtSsiPlayRules.CONTEXT_STEP_MS && it.startMs < start && it.id != hit.segmentId }
    val after = segments.filter { it.kind == YtSsiSegmentKind.SPEECH && it.startMs < end + YtSsiPlayRules.CONTEXT_STEP_MS && it.endMs > end && it.id != hit.segmentId }
    return Around(before.joinToString(" ") { it.text }.takeLast(400), after.joinToString(" ") { it.text }.take(400))
}

@Composable
private fun Compare(
    run: YtSsiRun,
    stances: Map<Long, YtSsiStance>,
    pending: Set<Long>,
    origins: List<YtSsiOrigin>,
    titles: Map<String, String>,
    ai: Boolean,
    palette: YtPalette,
    back: Int,
    ink: YtInk,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val byVideo = remember(origins) { YtSsiSources.byVideo(origins) }
    val compared = YtSsiRoute.compared(run.hits, YtSsiEvidenceState.chosen.toList(), YtSsiUi::key, stances)
    Column(modifier) {
        if (!ai) NoAi(palette)
        Text(
            stringResource(if (YtSsiEvidenceState.chosen.size >= 2) R.string.yt_r37_ssi_compare_chosen else R.string.yt_r37_ssi_compare_auto, YtSsiRoute.MAX_COMPARE),
            color = palette.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (compared.size < 2) {
            Empty(palette, stringResource(R.string.yt_r37_ssi_compare_few))
            return
        }
        // side by side: one column per hit, the row scrolls sideways on a narrow screen
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            for (hit in compared) {
                val context30 by produceState<Around?>(null, hit.videoId, hit.segmentId) { value = withContext(Dispatchers.IO) { around(hit) } }
                Column(
                    Modifier.width(if (compared.size == 2) 300.dp else 270.dp).clip(RoundedCornerShape(18.dp)).background(Color(back))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    HitHead(hit, ink, palette, byVideo[hit.videoId], titles)
                    StanceBlock(hit, stances[hit.segmentId], hit.segmentId in pending, ink)
                    Text(stringResource(R.string.yt_r37_ssi_passage), color = ink.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(hit.text, color = ink.text, fontSize = 13.sp, lineHeight = 18.sp)
                    context30?.let { a ->
                        if (a.before.isNotBlank()) {
                            Text(stringResource(R.string.yt_r37_ssi_before30), color = ink.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Text("…" + a.before, color = ink.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        if (a.after.isNotBlank()) {
                            Text(stringResource(R.string.yt_r37_ssi_after30), color = ink.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Text(a.after + "…", color = ink.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Small(stringResource(R.string.yt_r37_ssi_play), ink) { ssiPlay(context, hit) }
                        Small(stringResource(R.string.yt_r37_ssi_play_before), ink) {
                            MainScope().launch {
                                val segment = withContext(Dispatchers.IO) { hit.segmentId?.let { YtSsi.db.segment(it) } }
                                ssiPlayFrom(context, hit, (YtSsiPlayRules.playStart(hit, segment) - YtSsiPlayRules.CONTEXT_STEP_MS).coerceAtLeast(0))
                            }
                        }
                    }
                    Row(Modifier.padding(top = 6.dp)) { ChooseButton(hit, ink) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Timeline(
    run: YtSsiRun,
    stances: Map<Long, YtSsiStance>,
    origins: List<YtSsiOrigin>,
    titles: Map<String, String>,
    ai: Boolean,
    palette: YtPalette,
    back: Int,
    ink: YtInk,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberYtHaptics()
    val byVideo = remember(origins) { YtSsiSources.byVideo(origins) }
    val people = remember(run.hits) { YtSsiChronology.people(run.hits) }
    val person = YtSsiEvidenceState.person
    val shown = remember(run.hits, person) { if (person == null) run.hits else YtSsiChronology.of(run.hits, person) }
    val years = remember(shown) { YtSsiChronology.years(shown, ZoneId.systemDefault()) }
    val shifts = remember(shown, stances, person) { if (person == null) emptyList() else YtSsiChronology.shifts(shown, stances) }
    // the channel chips sit on the page, not on a card (Pitfall P1)
    val pageInk = remember(palette) { YtInk.on(palette, palette.background.toArgb()) }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        item {
            Column {
                if (!ai) NoAi(palette)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    val names = (listOfNotNull(person) + people).distinctBy { YtSsiText.fold(it) }
                    Small((if (person == null) "✓ " else "") + stringResource(R.string.yt_r37_ssi_timeline_all), pageInk) {
                        haptics.tick()
                        YtSsiEvidenceState.person = null
                    }
                    for (name in names) {
                        val on = person != null && YtSsiText.fold(name) == YtSsiText.fold(person)
                        Small((if (on) "✓ " else "") + name, pageInk) {
                            haptics.tick()
                            YtSsiEvidenceState.person = if (on) null else name
                        }
                    }
                }
                Text(
                    stringResource(if (person == null) R.string.yt_r37_ssi_timeline_hint else R.string.yt_r37_ssi_person_note),
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        if (shown.isEmpty()) item { Empty(palette, stringResource(R.string.yt_r37_ssi_empty)) }
        for (shift in shifts) {
            item {
                Column(cardModifier(back)) {
                    Text(
                        stringResource(
                            if (shift.kind == YtSsiShiftKind.CHANGED) R.string.yt_r37_ssi_shift_changed else R.string.yt_r37_ssi_shift_narrowed,
                            date(shift.earlier.uploaded).orEmpty(),
                            relationLabel(shift.earlierStance),
                            date(shift.later.uploaded).orEmpty(),
                            relationLabel(shift.laterStance),
                        ),
                        color = ink.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    shift.earlierStance.evidence?.let { e ->
                        Text("„" + e.quote + "“ (" + date(shift.earlier.uploaded).orEmpty() + ")", color = ink.text, fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp).clickable { playAt(context, shift.earlier, e.ms) })
                    }
                    shift.laterStance.evidence?.let { e ->
                        Text("„" + e.quote + "“ (" + date(shift.later.uploaded).orEmpty() + ")", color = ink.text, fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp).clickable { playAt(context, shift.later, e.ms) })
                    }
                    Text(stringResource(R.string.yt_r37_ssi_shift_note), color = ink.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        for (year in years) {
            item(key = "y:${year.year}") {
                Text(year.year?.toString() ?: stringResource(R.string.yt_r37_ssi_undated), color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            items(year.hits, key = { "t:" + YtSsiUi.key(it) }) { hit ->
                Column(cardModifier(back) { ssiPlay(context, hit) }) {
                    HitHead(hit, ink, palette, byVideo[hit.videoId], titles)
                    val stance = hit.segmentId?.let { stances[it] }
                    if (stance?.evidence == null) {
                        Text(hit.text, color = ink.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    StanceBlock(hit, stance, false, ink)
                }
            }
        }
    }
}

/** The labels of the Markdown export, from the string resources. */
internal fun reportLabels(context: Context): YtSsiReport.Labels = YtSsiReport.Labels(
    title = context.getString(R.string.yt_r37_ssi_report_title),
    question = context.getString(R.string.yt_r37_ssi_report_question),
    claim = context.getString(R.string.yt_r37_ssi_claim),
    created = context.getString(R.string.yt_r37_ssi_report_created),
    neutral = context.getString(R.string.yt_r37_ssi_neutral),
    groups = mapOf(
        YtSsiGroup.SUPPORTS to context.getString(R.string.yt_r37_ssi_group_supports),
        YtSsiGroup.REFUTES_REASONED to context.getString(R.string.yt_r37_ssi_group_refutes_reasoned),
        YtSsiGroup.CONTRADICTS to context.getString(R.string.yt_r37_ssi_group_contradicts),
        YtSsiGroup.QUALIFIES to context.getString(R.string.yt_r37_ssi_group_qualifies),
        YtSsiGroup.QUOTES to context.getString(R.string.yt_r37_ssi_group_quotes),
        YtSsiGroup.DISCUSSES to context.getString(R.string.yt_r37_ssi_group_discusses),
        YtSsiGroup.UNCERTAIN to context.getString(R.string.yt_r37_ssi_group_uncertain),
        YtSsiGroup.UNCHECKED to context.getString(R.string.yt_r37_ssi_group_unchecked),
    ),
    flags = mapOf(
        YtSsiContextFlag.IRONIC to context.getString(R.string.yt_r37_ssi_flag_ironic),
        YtSsiContextFlag.HYPOTHETICAL to context.getString(R.string.yt_r37_ssi_flag_hypothetical),
        YtSsiContextFlag.QUOTING_OTHER to context.getString(R.string.yt_r37_ssi_flag_quoting),
        YtSsiContextFlag.CRITICAL to context.getString(R.string.yt_r37_ssi_flag_critical),
    ),
    reason = context.getString(R.string.yt_r37_ssi_report_reason),
    notVerified = context.getString(R.string.yt_r37_ssi_not_evidenced),
    passageOnly = context.getString(R.string.yt_r37_ssi_report_passage),
    sameOrigin = context.getString(R.string.yt_r37_ssi_report_same_origin),
    originsCount = context.getString(R.string.yt_r37_ssi_origins),
    uncertainties = context.getString(R.string.yt_r37_ssi_report_uncertain),
    uncertainNotVerified = context.getString(R.string.yt_r37_ssi_report_u_not_verified),
    uncertainAi = context.getString(R.string.yt_r37_ssi_report_u_ai),
    uncertainUnchecked = context.getString(R.string.yt_r37_ssi_report_u_unchecked),
    uncertainNoAi = context.getString(R.string.yt_r37_ssi_report_u_no_ai),
    uncertainApprox = context.getString(R.string.yt_r37_ssi_report_u_approx),
    uncertainUndated = context.getString(R.string.yt_r37_ssi_report_u_undated),
    shift = context.getString(R.string.yt_r37_ssi_report_shift),
    shiftNote = context.getString(R.string.yt_r37_ssi_shift_note),
    notes = context.getString(R.string.yt_r37_ssi_notes),
    none = context.getString(R.string.yt_r37_ssi_report_none),
    approx = "≈ ",
)

/** A file name for an export: "ssi-belege-<query>-<date>.<ext>". */
private fun fileName(query: String, ext: String): String {
    val slug = YtSsiText.fold(query).replace(' ', '-').take(40).ifEmpty { "recherche" }
    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date())
    return "ssi-$slug-$day.$ext"
}

@Composable
private fun NotesField(palette: YtPalette, back: Int, ink: YtInk) {
    Text(stringResource(R.string.yt_r37_ssi_notes), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    BasicTextField(
        value = YtSsiEvidenceState.notes,
        onValueChange = { YtSsiEvidenceState.notes = it },
        textStyle = TextStyle(color = ink.text, fontSize = 14.sp, fontFamily = palette.font),
        cursorBrush = SolidColor(palette.accent),
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(back)).padding(12.dp),
        decorationBox = { inner ->
            Box {
                if (YtSsiEvidenceState.notes.isEmpty()) Text(stringResource(R.string.yt_r37_ssi_notes_hint), color = ink.textSecondary, fontSize = 14.sp)
                inner()
            }
        },
    )
}

/** "Recherche speichern": stores (or updates) the current research. */
internal fun saveResearch(context: Context, run: YtSsiRun, stances: Map<Long, YtSsiStance>) {
    if (run.query.isBlank()) return
    MainScope().launch {
        val now = System.currentTimeMillis()
        val id = YtSsiEvidenceState.researchId ?: YtSsiResearchStore.newId(now)
        val created = YtSsiEvidenceState.researchCreated.takeIf { YtSsiEvidenceState.researchId != null && it > 0 } ?: now
        val research = YtSsiResearchStore.build(
            id = id,
            createdAt = created,
            now = now,
            query = run.query,
            claim = YtSsiEvidenceState.claimOf(run.intent),
            hits = run.hits,
            stances = stances,
            chosen = YtSsiEvidenceState.chosen.toList(),
            key = YtSsiUi::key,
            notes = YtSsiEvidenceState.notes,
        )
        val ok = withContext(Dispatchers.IO) { runCatching { YtSsi.research.save(research) }.isSuccess }
        if (ok) {
            YtSsiEvidenceState.researchId = id
            YtSsiEvidenceState.researchCreated = created
            YtSsiResearchList.changed++
        }
        Toast.makeText(context, if (ok) R.string.yt_r37_ssi_saved_done else R.string.yt_r37_ssi_saved_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Report(
    context: Context,
    run: YtSsiRun,
    stances: Map<Long, YtSsiStance>,
    origins: List<YtSsiOrigin>,
    ai: Boolean,
    palette: YtPalette,
    back: Int,
    ink: YtInk,
    accentInk: YtInk,
    modifier: Modifier,
) {
    val person = YtSsiEvidenceState.person
    val report = remember(run.hits, stances, origins, ai, YtSsiEvidenceState.notes, YtSsiEvidenceState.claim, person) {
        YtSsiReport.build(run.query, YtSsiEvidenceState.claimOf(run.intent), run.hits, stances, origins, ai, System.currentTimeMillis(), YtSsiEvidenceState.notes, person)
    }
    val haptics = rememberYtHaptics()
    val pageInk = remember(palette) { YtInk.on(palette, palette.background.toArgb()) }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        item {
            Column(Modifier.padding(horizontal = 4.dp)) {
                if (!ai) NoAi(palette)
                Text(stringResource(R.string.yt_r37_ssi_report_question) + ": " + report.question, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (report.claim.isNotBlank()) Text(stringResource(R.string.yt_r37_ssi_claim) + ": " + report.claim, color = palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                Text(stringResource(R.string.yt_r37_ssi_origins, report.uncertainty.videos, report.uncertainty.origins), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.yt_r37_ssi_neutral), color = palette.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    Small(stringResource(R.string.yt_r37_ssi_export_md), pageInk) {
                        haptics.confirm()
                        YtSsiExport.launch(context, YtSsiExport.markdown, fileName(run.query, "md"), YtSsiReport.markdown(report, reportLabels(context)))
                    }
                    Small(stringResource(R.string.yt_r37_ssi_export_json), pageInk) {
                        haptics.confirm()
                        YtSsiExport.launch(context, YtSsiExport.json, fileName(run.query, "json"), YtSsiReport.json(report))
                    }
                    Small(stringResource(R.string.yt_r37_ssi_save), pageInk) {
                        haptics.confirm()
                        saveResearch(context, run, stances)
                    }
                }
                NotesField(palette, back, ink)
            }
        }
        if (report.groups.isEmpty()) item { Empty(palette, stringResource(R.string.yt_r37_ssi_empty)) }
        for ((group, findings) in report.groups) {
            item(key = "rg:${group.name}") {
                Text(groupLabel(group) + " (" + findings.size + ")", color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            items(findings, key = { "rf:" + YtSsiUi.key(it.hit) }) { f ->
                Column(cardModifier(back) { playAt(context, f.hit, f.ms) }) {
                    Text(
                        f.hit.title + " · " + YtSsiPlayRules.stamp(f.ms, f.timing),
                        color = ink.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(listOfNotNull(f.hit.channelName, date(f.hit.uploaded)).joinToString(" · "), color = ink.textSecondary, fontSize = 11.sp)
                    if (f.verified) {
                        Text("„" + f.quote + "“", color = ink.text, fontSize = 13.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text(
                            (if (f.stance != null) stringResource(R.string.yt_r37_ssi_not_evidenced) else stringResource(R.string.yt_r37_ssi_report_passage)) + ": „" + f.quote.take(240) + "“",
                            color = ink.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    f.stance?.reason?.let { Text(stringResource(R.string.yt_r37_ssi_reason, it.quote), color = ink.text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                    f.stance?.flags?.forEach { (flag, ev) -> Text(flagLabel(flag) + ": „" + ev.quote + "“", color = ink.textSecondary, fontSize = 12.sp) }
                }
            }
        }
        item(key = "uncertain") {
            val u = report.uncertainty
            Column(cardModifier(back)) {
                Text(stringResource(R.string.yt_r37_ssi_report_uncertain), color = ink.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val lines = buildList {
                    if (u.aiMissing) add(context.getString(R.string.yt_r37_ssi_report_u_no_ai))
                    if (u.notVerified > 0) add(context.getString(R.string.yt_r37_ssi_report_u_not_verified, u.notVerified))
                    if (u.aiUnsure > 0) add(context.getString(R.string.yt_r37_ssi_report_u_ai, u.aiUnsure))
                    if (u.unchecked > 0) add(context.getString(R.string.yt_r37_ssi_report_u_unchecked, u.unchecked))
                    if (u.approximate > 0) add(context.getString(R.string.yt_r37_ssi_report_u_approx, u.approximate))
                    if (u.undated > 0) add(context.getString(R.string.yt_r37_ssi_report_u_undated, u.undated))
                    for (s in u.shifts) add(context.getString(R.string.yt_r37_ssi_report_shift, s.earlier.channelName ?: "–", date(s.earlier.uploaded).orEmpty(), date(s.later.uploaded).orEmpty()) + " " + context.getString(R.string.yt_r37_ssi_shift_note))
                }.ifEmpty { listOf(context.getString(R.string.yt_r37_ssi_report_none)) }
                for (line in lines) Text("· $line", color = ink.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Bumped when the saved list changes, so the list reads it again. */
internal object YtSsiResearchList {
    var changed by mutableIntStateOf(0)
}

/** Opens a saved research: its hits as a finished search, its relations, notes and choice. */
internal fun openResearch(context: Context, research: YtSsiResearch, continueIt: Boolean) {
    MainScope().launch {
        YtSsiEvidenceState.newSearch(keepResearch = false)
        YtSsiEvidenceState.researchId = research.id
        YtSsiEvidenceState.researchCreated = research.createdAt
        YtSsiEvidenceState.notes = research.notes
        YtSsiUi.setField(research.query)
        if (continueIt) {
            // the same query again; relations come from the cache where the passages are the same
            val id = YtSsiUi.search(context, research.query, keepResearch = true)
            YtSsiEvidenceState.claim = research.claim
            YtSsiEvidenceState.routedFor = id
            YtSsiEvidenceState.forceAutoFor = id
            YtSsiEvidenceState.view = YtSsiView.EVIDENCE
            return@launch
        }
        val intent = withContext(Dispatchers.IO) { runCatching { YtSsiSearch.intentOf(research.query) }.getOrNull() }
            ?: YtSsiIntentParser.parse(research.query, System.currentTimeMillis(), ZoneId.systemDefault())
        val id = YtSsiSearch.restore(research.query, intent, research.hits.mapIndexed { i, h -> h.toHit(i + 1) })
        YtSsiEvidenceState.routedFor = id
        YtSsiEvidenceState.autoFor = id
        YtSsiEvidenceState.claim = research.claim
        YtSsiStances.restore(id, research.claim, research.stanceMap)
        YtSsiEvidenceState.chosen.addAll(research.chosen.take(YtSsiRoute.MAX_COMPARE))
        YtSsiEvidenceState.view = YtSsiView.EVIDENCE
    }
}

@Composable
private fun Saved(run: YtSsiRun, stances: Map<Long, YtSsiStance>, palette: YtPalette, back: Int, ink: YtInk, accentInk: YtInk, modifier: Modifier) {
    val context = LocalContext.current
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
    val list by produceState(emptyList<YtSsiResearch>(), YtSsiResearchList.changed) {
        value = withContext(Dispatchers.IO) { runCatching { YtSsi.research.list() }.getOrDefault(emptyList()) }
    }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        item {
            Column(Modifier.padding(horizontal = 4.dp)) {
                if (run.query.isNotBlank()) {
                    Text(
                        stringResource(if (YtSsiEvidenceState.researchId != null) R.string.yt_r37_ssi_save_update else R.string.yt_r37_ssi_save) + ": " + run.query,
                        color = accentInk.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.accent)
                            .clickable(role = Role.Button) {
                                haptics.confirm()
                                saveResearch(context, run, stances)
                            }
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Text(stringResource(R.string.yt_r37_ssi_save_hint), color = palette.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    NotesField(palette, back, ink)
                }
                Text(stringResource(R.string.yt_r37_ssi_saved_title, list.size), color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (list.isEmpty()) item { Empty(palette, stringResource(R.string.yt_r37_ssi_saved_none)) }
        items(list, key = { "s:" + it.id }) { research ->
            Column(cardModifier(back)) {
                Text(research.query, color = ink.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val verified = research.stances.count { it.verified }
                Text(
                    stringResource(R.string.yt_r37_ssi_saved_meta, date(research.updatedAt).orEmpty(), research.hits.size, verified, research.stances.size - verified),
                    color = ink.textSecondary,
                    fontSize = 12.sp,
                )
                if (research.notes.isNotBlank()) Text(research.notes, color = ink.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Small(stringResource(R.string.yt_r37_ssi_saved_open), ink) {
                        haptics.confirm()
                        openResearch(context, research, continueIt = false)
                    }
                    Small(stringResource(R.string.yt_r37_ssi_saved_continue), ink) {
                        haptics.confirm()
                        openResearch(context, research, continueIt = true)
                    }
                    val asking = confirmDelete == research.id
                    Small(stringResource(if (asking) R.string.yt_r37_ssi_saved_delete_sure else R.string.yt_r37_ssi_saved_delete), ink) {
                        if (!asking) {
                            haptics.tick()
                            confirmDelete = research.id
                            return@Small
                        }
                        haptics.confirm()
                        confirmDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) { YtSsi.research.delete(research.id) }
                            if (YtSsiEvidenceState.researchId == research.id) YtSsiEvidenceState.researchId = null
                            YtSsiResearchList.changed++
                        }
                    }
                }
            }
        }
    }
}
