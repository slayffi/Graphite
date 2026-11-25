package com.graphite.torrent.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.CrystalLogo
import com.graphite.torrent.ui.designsystem.components.EmptyState
import com.graphite.torrent.ui.designsystem.components.FilterChip
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteTabs
import com.graphite.torrent.ui.designsystem.components.PieceMap
import com.graphite.torrent.ui.designsystem.components.SummaryHeader
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.components.SettingsGroup
import com.graphite.torrent.ui.designsystem.components.TorrentCard
import com.graphite.torrent.ui.designsystem.components.TorrentCardState
import com.graphite.torrent.ui.designsystem.components.TreeRow
import com.graphite.torrent.ui.designsystem.components.VisualStatus
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the component gallery to build/gallery/components.png so the design can be reviewed. */
class ComponentGalleryRenderTest {
    @Test
    fun renderGallery() {
        val image = ImageComposeScene(width = 1600, height = 1300, density = Density(2f)) {
            GraphiteTheme(units = UnitLabels.Russian) {
                val colors = GraphiteTheme.colors
                Row(Modifier.fillMaxSize().background(colors.bg).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryHeader("24,8 МБ/с", "Общая скорость", List(60) { ((sin(it / 5.0) + 1.4) * 9_000_000).toLong() }, "Активных: 3 из 7", "Отдача: 0 Б ✓")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip("Все", true, {}, count = 7)
                            FilterChip("Активные", false, {}, count = 3)
                            FilterChip("Готово", false, {}, count = 2)
                        }
                        TorrentCard(TorrentCardState("1", "Some.Series.S02E07.Episode.Title.2160p.WEB-DL.DDP5.1.HDR.mkv", VisualStatus.DOWNLOADING, "Загрузка", 0.62f, "62%", "12,4 МБ/с", "2,8 из 4,5 ГБ", "2м 18с"), {})
                        TorrentCard(TorrentCardState("2", "Ubuntu 26.04 Desktop amd64.iso", VisualStatus.PAUSED, "Пауза", 0.31f, "31%", "", "1,9 из 6,1 ГБ", ""), {})
                        TorrentCard(TorrentCardState("3", "Album — FLAC 24bit", VisualStatus.ERROR, "Ошибка", 0.1f, "10%", "", "64 из 640 МБ", "", errorText = "Нет места на диске. Освободите место или выберите другую папку."), {})
                        TorrentCard(TorrentCardState("4", "Documentary.1080p.mkv", VisualStatus.COMPLETED, "Готово", 1f, "100%", "", "3,2 ГБ", ""), {})
                    }
                    Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GraphiteTabs(listOf("Обзор", "Файлы", "Пиры", "Трекеры", "Куски"), 1, {})
                        TreeRow("Season 2", "4 файла · 12,3 ГБ", 0, GraphiteIcons.Folder, ToggleableState.Indeterminate, {}, expanded = true, onExpandClick = {})
                        TreeRow("S02E07.2160p.mkv", "2,8 ГБ · 40%", 1, GraphiteIcons.FileVideo, ToggleableState.On, {}, progress = 0.4f)
                        PieceMap(ByteArray(700) { i -> (if (i < 300) 3 else if (i < 320) 2 else if (i < 560) 1 else 0).toByte() })
                        SettingsGroup("Скорость") {
                            SwitchSettingRow("Нулевая отдача", "Не отдавать данные другим пирам", true, {})
                            SwitchSettingRow("Предвыделение места", "Резервировать место на диске заранее", false, {})
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GraphiteButton("Скачать", {}, icon = GraphiteIcons.Download)
                            GraphiteButton("Отмена", {}, kind = ButtonKind.SECONDARY)
                        }
                    }
                }
            }
        }.use { scene -> scene.render() }
        val out = File("build/gallery").apply { mkdirs() }
        val png = File(out, "components.png")
        png.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        assertTrue(png.length() > 10_000)

        val brand = ImageComposeScene(width = 800, height = 700, density = Density(2f)) {
            GraphiteTheme(units = UnitLabels.Russian) {
                Row(Modifier.fillMaxSize().background(GraphiteTheme.colors.bg).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CrystalLogo(size = 140.dp)
                    EmptyState("Добавьте первую загрузку", Modifier.width(200.dp), message = "Файл, magnet-ссылка или QR-код", actionLabel = "Добавить", onAction = {})
                }
            }
        }.use { it.render() }
        File(out, "brand.png").writeBytes(brand.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
