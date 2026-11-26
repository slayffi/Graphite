package com.graphite.torrent.ui.app.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.RssRule
import com.graphite.torrent.data.automation.RssService
import com.graphite.torrent.ui.app.AppEnvironment
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.details.formatDate
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.ButtonRow
import com.graphite.torrent.ui.designsystem.components.DetailRow
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteIconButton
import com.graphite.torrent.ui.designsystem.components.GraphiteSheet
import com.graphite.torrent.ui.designsystem.components.GraphiteTextField
import com.graphite.torrent.ui.designsystem.components.SectionHeader
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import com.graphite.torrent.core.model.GraphiteException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun RssScreen(env: AppEnvironment, rss: RssService, onBack: () -> Unit, onShowMessage: (String) -> Unit) {
    val feeds by rss.feeds.collectAsStateWithLifecycle(emptyList())
    val rules by rss.rules.collectAsStateWithLifecycle(emptyList())
    val items by rss.recentItems.collectAsStateWithLifecycle(emptyList())
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newFeed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RssRule?>(null) }
    val spacing = GraphiteTheme.spacing

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            GraphiteIconButton(GraphiteIcons.ArrowLeft, stringResource(Res.string.back), onBack)
            Text(stringResource(Res.string.rss_title), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.weight(1f).padding(start = spacing.xs))
            GraphiteIconButton(GraphiteIcons.RefreshCw, stringResource(Res.string.rss_refresh), {
                scope.launch { onShowMessage(getString(Res.string.rss_refreshed, rss.refreshAll())) }
            })
        }
        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            item {
                GraphiteCard(contentPadding = spacing.xxs) {
                    SwitchSettingRow(stringResource(Res.string.rss_enabled), stringResource(Res.string.rss_desc), settings.rssEnabled, { v ->
                        scope.launch { env.settings.update { it.copy(rssEnabled = v) } }
                    }, icon = GraphiteIcons.Rss)
                }
            }
            item { SectionHeader(stringResource(Res.string.rss_feeds)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GraphiteTextField(newFeed, { newFeed = it }, Modifier.weight(1f), placeholder = "https://…/rss", leadingIcon = GraphiteIcons.Link)
                    Spacer(Modifier.width(spacing.xs))
                    GraphiteIconButton(GraphiteIcons.Plus, stringResource(Res.string.rss_add_feed), {
                        val url = newFeed
                        scope.launch {
                            try {
                                rss.addFeed(url)
                                newFeed = ""
                            } catch (e: GraphiteException) {
                                onShowMessage(getString(e.error.kind.titleRes()) + ". " + getString(e.error.kind.adviceRes()))
                            }
                        }
                    })
                }
            }
            items(feeds, key = { "feed${it.id}" }) { feed ->
                GraphiteCard(contentPadding = spacing.xs) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DetailRow(
                                title = feed.title.ifBlank { feed.url },
                                subtitle = buildString {
                                    append(feed.url)
                                    if (feed.lastChecked > 0) append(" · ").append(formatDate(feed.lastChecked))
                                    if (feed.lastError.isNotBlank()) append(" · ").append(feed.lastError)
                                },
                                value = "",
                                icon = if (feed.lastError.isNotBlank()) GraphiteIcons.CircleAlert else GraphiteIcons.Rss,
                                iconTint = if (feed.lastError.isNotBlank()) GraphiteTheme.colors.error else GraphiteTheme.colors.textSecondary,
                            )
                        }
                        com.graphite.torrent.ui.designsystem.components.GraphiteSwitch(feed.enabled, { v -> scope.launch { rss.setFeedEnabled(feed.id, v) } })
                        GraphiteIconButton(GraphiteIcons.Trash2, stringResource(Res.string.delete), { scope.launch { rss.deleteFeed(feed.id) } }, tint = GraphiteTheme.colors.errorText)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(Res.string.rss_rules), Modifier.weight(1f))
                    GraphiteButton(stringResource(Res.string.rss_add_rule), { editing = RssRule() }, kind = ButtonKind.TERTIARY, icon = GraphiteIcons.Plus)
                }
            }
            if (rules.isEmpty()) {
                item { Text(stringResource(Res.string.rss_rules_empty), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary) }
            }
            items(rules, key = { "rule${it.id}" }) { rule ->
                GraphiteCard(onClick = { editing = rule }) {
                    DetailRow(
                        title = rule.name.ifBlank { rule.include },
                        subtitle = listOfNotNull(
                            rule.include.takeIf { it.isNotBlank() }?.let { "+ $it" },
                            rule.exclude.takeIf { it.isNotBlank() }?.let { "− $it" },
                            rule.category,
                            feeds.firstOrNull { it.id == rule.feedId }?.title,
                        ).joinToString(" · "),
                        value = "",
                        icon = if (rule.enabled) GraphiteIcons.Funnel else GraphiteIcons.EyeOff,
                    )
                }
            }
            item { SectionHeader(stringResource(Res.string.rss_recent)) }
            items(items.take(50), key = { "item${it.feedId}-${it.guid}" }) { entry ->
                DetailRow(
                    title = entry.title,
                    subtitle = if (entry.published > 0) formatDate(entry.published) else "",
                    value = if (entry.downloaded) "✓" else "",
                    icon = if (entry.downloaded) GraphiteIcons.CircleCheck else GraphiteIcons.File,
                )
            }
        }
    }

    editing?.let { rule ->
        RssRuleEditor(rule, onDismiss = { editing = null }, onSave = {
            scope.launch { rss.saveRule(it) }
            editing = null
        }, onDelete = if (rule.id != 0L) ({
            scope.launch { rss.deleteRule(rule.id) }
            editing = null
        }) else null)
    }
}

@Composable
private fun RssRuleEditor(initial: RssRule, onDismiss: () -> Unit, onSave: (RssRule) -> Unit, onDelete: (() -> Unit)?) {
    var rule by remember { mutableStateOf(initial) }
    val spacing = GraphiteTheme.spacing
    GraphiteSheet(onDismiss = onDismiss, title = stringResource(Res.string.rss_rule)) {
        GraphiteTextField(rule.name, { rule = rule.copy(name = it) }, label = stringResource(Res.string.rss_rule_name))
        Spacer(Modifier.height(spacing.xs))
        GraphiteTextField(rule.include, { rule = rule.copy(include = it) }, label = stringResource(Res.string.rss_include), supportingText = stringResource(Res.string.rss_include_hint))
        Spacer(Modifier.height(spacing.xs))
        GraphiteTextField(rule.exclude, { rule = rule.copy(exclude = it) }, label = stringResource(Res.string.rss_exclude))
        Spacer(Modifier.height(spacing.xs))
        GraphiteTextField(rule.category.orEmpty(), { rule = rule.copy(category = it.ifBlank { null }) }, label = stringResource(Res.string.info_category), leadingIcon = GraphiteIcons.Layers)
        SwitchSettingRow(stringResource(Res.string.rss_regex), stringResource(Res.string.rss_regex_desc), rule.useRegex, { rule = rule.copy(useRegex = it) })
        SwitchSettingRow(stringResource(Res.string.start_paused), null, rule.startPaused, { rule = rule.copy(startPaused = it) })
        SwitchSettingRow(stringResource(Res.string.sched_rule_enabled), null, rule.enabled, { rule = rule.copy(enabled = it) })
        Spacer(Modifier.height(spacing.md))
        ButtonRow(Modifier.fillMaxWidth()) {
            if (onDelete != null) GraphiteButton(stringResource(Res.string.delete), onDelete, kind = ButtonKind.DANGER)
            Spacer(Modifier.weight(1f))
            GraphiteButton(stringResource(Res.string.cancel), onDismiss, kind = ButtonKind.SECONDARY)
            GraphiteButton(stringResource(Res.string.save), { onSave(rule) }, enabled = rule.include.isNotBlank() || rule.name.isNotBlank())
        }
    }
}
