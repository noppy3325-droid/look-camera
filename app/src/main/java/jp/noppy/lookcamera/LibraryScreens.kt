package jp.noppy.lookcamera

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun PageBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        actions()
    }
}

@Composable
internal fun GalleryScreen(photos: List<Photo>, grid: LazyGridState, back: () -> Unit, open: (Photo) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageBar("ギャラリー", back) {
            if (photos.isNotEmpty()) Text("${photos.size} 枚", Modifier.padding(end = 12.dp), color = Muted)
        }
        if (photos.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_photo_library), null, Modifier.size(44.dp), tint = Muted)
                Spacer(Modifier.height(16.dp))
                Text("撮影した写真はまだありません")
                TextButton(back) { Text("カメラに戻る") }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
                state = grid, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(photos, key = { it.uri.toString() }) { photo ->
                    AsyncImage(photo.uri, "撮影した写真 ${date(photo.timestamp)}",
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(5.dp))
                            .background(SurfaceRaised).clickable { open(photo) }, contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
internal fun PhotoScreen(photos: List<Photo>, initialUri: String?, model: CameraModel, back: () -> Unit,
                         showGallery: () -> Unit, fromGallery: Boolean) {
    val context = LocalContext.current
    val initialPage = photos.indexOfFirst { it.uri.toString() == initialUri }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { photos.size }
    val photo = photos.getOrNull(pager.currentPage)
    val look by produceState<LookSelection?>(null, photo) {
        value = null
        value = photo?.let { model.readLook(it) }
    }
    var zoomed by remember(photo?.uri) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageBar(if (photo == null) "写真" else "${pager.currentPage + 1} / ${photos.size}", back) {
            if (!fromGallery) {
                IconButton(showGallery) { Icon(painterResource(R.drawable.ic_photo_library), "ギャラリー") }
            }
            if (photo != null) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, photo.uri)
                        clipData = ClipData.newUri(context.contentResolver, "JPEG", photo.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "写真を共有"))
                }) { Icon(Icons.Outlined.Share, "共有") }
            }
        }
        if (photo == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("写真を表示できません")
            }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !zoomed, key = { photos[it].uri.toString() }) { page ->
                ZoomablePhoto(photos[page], Modifier.fillMaxSize(), reset = pager.currentPage != page) { isZoomed ->
                    if (pager.currentPage == page) zoomed = isZoomed
                }
            }
            val selection = look
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(selection?.preset?.name ?: "写真", color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(date(photo.timestamp), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (selection != null && selection.preset != Looks.off) {
                        Text("${(selection.amount * 100).roundToInt()}%", fontFamily = FontFamily.Monospace)
                    }
                    if (photo.width > 0 && photo.height > 0) {
                        Text("${photo.width} × ${photo.height}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(photo: Photo, modifier: Modifier, reset: Boolean, onZoomed: (Boolean) -> Unit) {
    var zoom by remember(photo.uri) { mutableFloatStateOf(1f) }
    var pan by remember(photo.uri) { mutableStateOf(Offset.Zero) }
    var failed by remember(photo.uri) { mutableStateOf(false) }
    LaunchedEffect(reset) {
        if (reset) {
            zoom = 1f
            pan = Offset.Zero
        }
    }
    val isZoomed = zoom > 1.01f
    LaunchedEffect(isZoomed, reset) { if (!reset) onZoomed(isZoomed) }
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF0C0B0A))) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
            val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
            val center = Offset(width * .5f, height * .5f)
            val pivot = if (centroid == Offset.Unspecified) center else centroid
            val shift = (pivot - center - pan) * (1f - newZoom / zoom)
            zoom = newZoom
            val ratio = if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else width / height
            val fittedWidth = minOf(width, height * ratio)
            val fittedHeight = minOf(height, width / ratio)
            val limitX = ((fittedWidth * zoom - width) * .5f).coerceAtLeast(0f)
            val limitY = ((fittedHeight * zoom - height) * .5f).coerceAtLeast(0f)
            pan = Offset((pan.x + panChange.x + shift.x).coerceIn(-limitX, limitX),
                (pan.y + panChange.y + shift.y).coerceIn(-limitY, limitY))
        }
        Box(Modifier.fillMaxSize().transformable(transform, canPan = { isZoomed })
            .pointerInput(photo.uri) {
                detectTapGestures(onDoubleTap = {
                    zoom = if (zoom > 1f) 1f else 2.5f
                    pan = Offset.Zero
                })
            }) {
            AsyncImage(photo.uri, "保存した写真", Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            }, contentScale = ContentScale.Fit, onError = { failed = true }, onSuccess = { failed = false })
            if (failed) Text("写真を表示できません", Modifier.align(Alignment.Center), color = Muted)
        }
    }
}

@Composable
internal fun SettingsScreen(settings: CameraSettings, model: CameraModel, back: () -> Unit, licenses: () -> Unit) {
    var quality by remember(settings.quality) { mutableFloatStateOf(settings.quality.toFloat()) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        PageBar("設定", back)
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsGroup("撮影") {
                Text("既定のアスペクト比", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (frame in Framing.entries) {
                        FilterChip(frame == settings.framing, onClick = { model.setDefaultFraming(frame) },
                            label = { Text(frame.label) })
                    }
                }
                HorizontalDivider(color = Border)
                SettingsToggle("構図グリッド", "画面を三分割して構図を整える", settings.showGrid, model::setGrid)
                HorizontalDivider(color = Border)
                SettingsToggle("前回の Look を復元", "次回起動時に色と強度を引き継ぐ", settings.restoreLook, model::setRestore)
            }
            SettingsGroup("保存") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("JPEG 保存品質", style = MaterialTheme.typography.titleSmall)
                    Text("${quality.roundToInt()}%", fontFamily = FontFamily.Monospace, color = Accent)
                }
                Slider(quality, { quality = it }, valueRange = 70f..100f,
                    onValueChangeFinished = { model.setQuality(quality.roundToInt()) })
                Text("品質を上げると、写真のファイルサイズも大きくなります。",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            SettingsGroup("アプリ情報") {
                Text("Look Camera 0.3", style = MaterialTheme.typography.titleMedium)
                Text("撮影・画像処理・保存は端末内で行います。", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(licenses) { Text("オープンソースライセンス") }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 5.dp), color = Accent, style = MaterialTheme.typography.labelLarge)
        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceRaised) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onCheckedChange = change,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun LicenseScreen(back: () -> Unit) {
    val context = LocalContext.current
    val license by produceState("", context) {
        value = withContext(Dispatchers.IO) { context.assets.open("licenses/apache-2.0.txt").bufferedReader().use { it.readText() } }
    }
    Column(Modifier.fillMaxSize()) {
        PageBar("オープンソースライセンス", back)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("AndroidX（CameraX、Compose、DataStore）\nKotlin / kotlinx.coroutines\nCoil\nGoogle Material Icons",
                style = MaterialTheme.typography.titleSmall)
            Text(license, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun date(timestamp: Long): String =
    if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)) else ""

