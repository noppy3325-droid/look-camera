package jp.noppy.lookcamera

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val FinderShape = RoundedCornerShape(4.dp)

@Composable
internal fun CameraScreen(
    model: CameraModel, state: CameraUiState, front: Boolean, flash: Flash,
    onFlash: () -> Unit, onFlip: () -> Unit, onSettings: () -> Unit, onPhotos: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    var exposureOpen by rememberSaveable { mutableStateOf(false) }
    var shutterPulse by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf(state.look.preset.category?.name ?: Category.FILM.name) }
    var intensityOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(lookOpen || exposureOpen) {
        if (lookOpen) lookOpen = false else exposureOpen = false
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val controlWidth = minOf(342.dp, maxWidth * .47f)
        val compact = landscape && maxHeight < 360.dp
        val view = remember(front, state.framing, retry, landscape) {
            PreviewView(context).apply {
                keepScreenOn = true
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
        DisposableEffect(view, owner) {
            val active = CameraSession(context, owner, view, front, flash, state.look)
            session = active
            onDispose {
                active.close()
                session = null
            }
        }
        SideEffect {
            session?.setLook(state.look)
            session?.setFlash(flash)
        }

        val toolbar = @Composable {
            CameraToolbar(session, state, flash, exposureOpen, onFlash, model::setFraming, onSettings) {
                exposureOpen = !exposureOpen
                lookOpen = false
            }
        }
        val controls = @Composable {
            CameraControls(state, session, compact, lookOpen, onFlip, onPhotos, toggleLook = {
                lookOpen = !lookOpen
                exposureOpen = false
            }) {
                if (session?.shoot(model) == true) {
                    shutterPulse++
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            }
        }
        val finder = @Composable { modifier: Modifier ->
            Finder(modifier, view, session, state.framing, landscape, exposureOpen, lookOpen, state.capturing,
                state.settings.showGrid, shutterPulse, { retry++ }) {
                if (lookOpen) BoxWithConstraints(Modifier.align(Alignment.BottomCenter)
                    .padding(8.dp).widthIn(max = 460.dp).fillMaxWidth()) {
                    FilmTray(state, model, category, { category = it }, intensityOpen,
                        { intensityOpen = !intensityOpen }, Modifier.fillMaxWidth()
                            .heightIn(max = minOf(218.dp, maxHeight * .72f)))
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                finder(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(controlWidth).fillMaxHeight().padding(start = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    toolbar()
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                toolbar()
                finder(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp))
                controls()
            }
        }
    }
}

@Composable
private fun CameraToolbar(session: CameraSession?, state: CameraUiState, flash: Flash, exposureOpen: Boolean,
                          onFlash: () -> Unit, onFrame: (Framing) -> Unit, onSettings: () -> Unit, toggleExposure: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        val icon = when (flash) {
            Flash.OFF -> R.drawable.ic_flash_off
            Flash.AUTO -> R.drawable.ic_flash_auto
            Flash.ON -> R.drawable.ic_flash_on
        }
        IconButton(onFlash, enabled = session?.hasFlash == true && !state.capturing) {
            Icon(painterResource(icon), flash.label, tint = when {
                session?.hasFlash != true -> Border
                flash != Flash.OFF -> Accent
                else -> Paper
            })
        }
        var framingMenu by remember { mutableStateOf(false) }
        Box {
            TextButton({ framingMenu = true }, enabled = !state.capturing,
                modifier = Modifier.semantics { contentDescription = "アスペクト比 ${state.framing.label}" }) {
                Text(state.framing.label, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            DropdownMenu(expanded = framingMenu, onDismissRequest = { framingMenu = false }) {
                Framing.entries.forEach { framing ->
                    DropdownMenuItem(
                        text = { Text(framing.label, color = if (framing == state.framing) Accent else Paper) },
                        onClick = { framingMenu = false; onFrame(framing) },
                        modifier = Modifier.semantics { selected = framing == state.framing },
                    )
                }
            }
        }
        val exposure = session?.exposure
        TextButton(toggleExposure, enabled = exposure != null && !state.capturing,
            modifier = Modifier.semantics { contentDescription = "露出補正" }) {
            Text(exposure?.let { evLabel(it.ev) } ?: "EV", fontFamily = FontFamily.Monospace,
                color = if (exposureOpen) Accent else if (exposure == null) Muted else Paper)
        }
        IconButton(onSettings, enabled = !state.capturing) { Icon(Icons.Outlined.Settings, "設定") }
    }
}

@Composable
private fun Finder(modifier: Modifier, view: PreviewView, session: CameraSession?, framing: Framing,
                   landscape: Boolean, exposureOpen: Boolean, lookOpen: Boolean, capturing: Boolean, showGrid: Boolean,
                   shutterPulse: Int, retry: () -> Unit, overlay: @Composable BoxScope.() -> Unit) {
    val blink = remember { Animatable(0f) }
    LaunchedEffect(shutterPulse) {
        if (shutterPulse > 0) {
            blink.snapTo(.58f)
            blink.animateTo(0f, tween(180))
        }
    }
    var focus by remember(view) { mutableStateOf<Offset?>(null) }
    var focusSequence by remember(view) { mutableIntStateOf(0) }
    LaunchedEffect(focusSequence) {
        if (focus != null) {
            delay(1000)
            focus = null
        }
    }
    val currentZoom by rememberUpdatedState(session?.zoom?.zoomRatio ?: 1f)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (landscape) framing.width.toFloat() / framing.height else framing.height.toFloat() / framing.width
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        Box(Modifier.size(width, height).clip(FinderShape).background(Color(0xFF090B0A))
            .border(1.dp, Border, FinderShape)) {
            key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
            if (session?.streaming == true && session.error == null) {
                Box(Modifier.fillMaxSize()
                    .pointerInput(session) {
                        detectTapGestures { point ->
                            session.focus(point.x, point.y)
                            focus = point
                            focusSequence++
                        }
                    }
                    .pointerInput(session) {
                        var target = currentZoom
                        var lastEvent = 0L
                        detectTransformGestures { _, _, zoom, _ ->
                            if (abs(zoom - 1f) > .001f) {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastEvent > 120) target = currentZoom
                                val limits = session.zoom ?: return@detectTransformGestures
                                target = (target * zoom).coerceIn(limits.minZoomRatio, limits.maxZoomRatio)
                                session.zoomTo(target)
                                lastEvent = now
                            }
                        }
                    })
                if (showGrid) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (third in listOf(1f / 3f, 2f / 3f)) {
                            val x = size.width * third
                            val y = size.height * third
                            val color = Color.White.copy(alpha = .35f)
                            drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                    }
                }
                focus?.let { point ->
                    Box(Modifier.offset { IntOffset(point.x.roundToInt() - 25.dp.roundToPx(),
                        point.y.roundToInt() - 25.dp.roundToPx()) }
                        .size(50.dp).border(1.dp, Accent, RoundedCornerShape(6.dp)))
                }
                val exposure = session.exposure
                if (exposureOpen && exposure != null) {
                    ExposureDial(exposure, session::setExposure, !capturing, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
                session.zoom?.takeUnless { lookOpen }?.let {
                    ZoomDial(it, session::zoomTo, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
            } else if (session?.error != null) {
                Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(session.error.orEmpty(), textAlign = TextAlign.Center)
                    TextButton(onClick = retry) { Text("再試行") }
                }
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp)
            }
            Box(Modifier.matchParentSize().alpha(blink.value).background(Color.Black))
            overlay()
        }
    }
}

private fun evLabel(ev: Float) = if (ev == 0f) "±0.0" else String.format(Locale.US, "%+.1f", ev)

@Composable
private fun ExposureDial(exposure: ExposureReading, onChange: (Int) -> Unit, enabled: Boolean, modifier: Modifier) {
    val lower = exposure.range.first
    val upper = exposure.range.last
    if (upper <= lower) return
    Column(modifier.width(210.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceDark.copy(alpha = .92f))
        .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(evLabel(exposure.ev) + " EV", color = Accent, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge)
            TextButton({ onChange(0) }, enabled = enabled && exposure.index != 0) { Text("リセット") }
        }
        Slider(value = exposure.index.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            enabled = enabled, valueRange = lower.toFloat()..upper.toFloat(),
            steps = (upper - lower - 1).coerceAtLeast(0),
            modifier = Modifier.semantics { contentDescription = "露出補正ダイヤル" })
    }
}

@Composable
private fun ZoomDial(zoom: ZoomReading, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(zoom)
    val choices = listOf(zoom.minZoomRatio, 1f, 2f, 4f).filter {
        it <= zoom.maxZoomRatio && (it >= 1f || it == zoom.minZoomRatio)
    }.distinct().let { if (zoom.minZoomRatio > 1f) it.filter { ratio -> ratio >= zoom.minZoomRatio } else it }
    val nearest = choices.minByOrNull { abs(it - zoom.zoomRatio) }
    val value = String.format(Locale.US, "%.1f×", zoom.zoomRatio)
    Row(modifier.clip(CircleShape).background(Color(0xD9181613)).border(1.dp, Color(0x88FFFFFF), CircleShape)
        .pointerInput(Unit) {
            var target = 1f
            detectHorizontalDragGestures(
                onDragStart = { target = current.zoomRatio },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    target = (target * exp(-dx / 240f)).coerceIn(current.minZoomRatio, current.maxZoomRatio)
                    onZoom(target)
                },
            )
        }.padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (ratio in choices) {
            val selected = ratio == nearest
            val tick = if (ratio == ratio.toInt().toFloat()) "${ratio.toInt()}×"
                else String.format(Locale.US, "%.1f×", ratio)
            TextButton(onClick = { onZoom(ratio) },
                modifier = Modifier.width(if (selected) 54.dp else 45.dp)
                                        .semantics {
                        contentDescription = "ズーム $tick"
                        this.selected = selected
                        if (selected) stateDescription = value
                    },
                contentPadding = PaddingValues(0.dp)) {
                Text(if (selected) value else tick, Modifier.clearAndSetSemantics {}, color = if (selected) Accent else Paper,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CameraControls(state: CameraUiState, session: CameraSession?, compact: Boolean,
                           lookOpen: Boolean, onFlip: () -> Unit, onPhotos: () -> Unit,
                           toggleLook: () -> Unit, shoot: () -> Unit) {
    val active = state.look.preset
    val selected = active != Looks.off
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 5.dp, bottom = 3.dp)) {
        TextButton(onClick = toggleLook,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
                .semantics { contentDescription = if (lookOpen) "フィルム選択を閉じる" else "フィルムを選ぶ" },
            colors = ButtonDefaults.textButtonColors(containerColor = SurfaceRaised),
            shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            val tile = RoundedCornerShape(3.dp)
            Box(Modifier.size(26.dp).clip(tile).background(Border).border(1.dp, Border, tile)) {
                state.thumbnails[active.id]?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(active.name, color = if (selected) Accent else Paper, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = .5.sp)
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Text("${(state.look.amount * 100).roundToInt()}%", color = Muted,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(if (compact) 68.dp else 86.dp).padding(horizontal = 24.dp)) {
            val photo = state.photos.firstOrNull()
            FilledTonalIconButton(onPhotos, enabled = !state.capturing, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
                if (photo == null) {
                    Icon(painterResource(R.drawable.ic_photo_library), "ギャラリー")
                } else {
                    AsyncImage(photo.uri, "直近の写真", Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop)
                }
            }
            if (state.pendingPhotos > 0) {
                Text("保存中 ${state.pendingPhotos}", modifier = Modifier.align(Alignment.BottomStart),
                    color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            val ready = session?.streaming == true && session.error == null && state.canCapture
            IconButton(shoot, modifier = Modifier.align(Alignment.Center).size(if (compact) 62.dp else 70.dp)
                .border(2.dp, if (ready || state.capturing) Paper else Border, CircleShape)
                .semantics { contentDescription = "シャッター" },
                enabled = ready) {
                if (state.capturing || state.pendingPhotos >= MAX_PENDING_PHOTOS) {
                    CircularProgressIndicator(Modifier.size(30.dp), color = Accent, strokeWidth = 2.dp)
                } else {
                    Box(Modifier.fillMaxSize().padding(6.dp).clip(CircleShape).background(if (ready) Paper else Border))
                }
            }
            FilledTonalIconButton(onFlip, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
                enabled = session?.canFlip == true && !state.capturing) {
                Icon(painterResource(R.drawable.ic_camera_switch), "前面・背面カメラ切替")
            }
        }
    }
}


@Composable
private fun FilmTray(state: CameraUiState, model: CameraModel, category: String, onCategory: (String) -> Unit,
                     intensityOpen: Boolean, onIntensity: () -> Unit, modifier: Modifier = Modifier) {
    val active = state.look.preset
    val selected = active != Looks.off
    val favorite = active.id in state.settings.favorites
    val categories = Category.entries.map { it.name to it.label } + ("FAVORITES" to "★")
    val visible = listOf(Looks.off) + Looks.all.filter {
        if (category == "FAVORITES") it.id in state.settings.favorites else it.category?.name == category
    }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape)
        .background(SurfaceDark.copy(alpha = .96f)).border(1.dp, Border, shape)
        .verticalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
        if (selected) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(42.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { model.toggleFavorite(active) }) {
                    if (favorite) {
                        Icon(Icons.Outlined.Star, "お気に入りから削除", tint = Accent)
                    } else {
                        Icon(painterResource(R.drawable.ic_star_border), "お気に入りに追加", tint = Muted)
                    }
                }
                TextButton(onClick = { onIntensity() },
                    modifier = Modifier.semantics { contentDescription = "Look 強度" }) {
                    Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${(state.look.amount * 100).roundToInt()}%", fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (selected && intensityOpen) {
            Row(Modifier.padding(horizontal = 16.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("強度", color = Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                Slider(value = state.look.amount, onValueChange = model::setIntensity,
                onValueChangeFinished = model::saveIntensity,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Look 強度調整" })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            for ((id, label) in categories) {
                TextButton(onClick = { onCategory(id) }, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.semantics { if (id == "FAVORITES") contentDescription = "お気に入り" }) {
                    Text(label, maxLines = 1, color = if (category == id) Accent else Muted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        key(category) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { preset ->
                    val chosen = active == preset
                    val shape = RoundedCornerShape(4.dp)
                    Column(Modifier.width(70.dp).clip(shape)
                        .selectable(chosen, role = Role.Button, onClick = { model.select(preset) })
                        .semantics { contentDescription = preset.name },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(52.dp).clip(shape)
                            .background(SurfaceRaised).border(if (chosen) 2.dp else 1.dp,
                                if (chosen) Accent else Border, shape).padding(if (chosen) 2.dp else 1.dp)
                            .clip(RoundedCornerShape(5.dp))) {
                            state.thumbnails[preset.id]?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Text(preset.name, Modifier.padding(top = 4.dp).heightIn(min = 24.dp),
                            color = if (chosen) Accent else Paper, textAlign = TextAlign.Center,
                            fontSize = 10.sp, lineHeight = 11.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
