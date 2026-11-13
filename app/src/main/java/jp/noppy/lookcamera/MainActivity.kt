package jp.noppy.lookcamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.setContent

internal val SurfaceDark = Color(0xFF121110)
internal val SurfaceRaised = Color(0xFF211F1C)
internal val Border = Color(0xFF49423A)
internal val Accent = Color(0xFFE8C88F)
internal val Paper = Color(0xFFF6F0E6)
internal val Muted = Color(0xFFB8AFA3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Color(0xFF2E2214), surface = SurfaceDark,
                background = SurfaceDark, onSurface = Paper, onBackground = Paper,
                surfaceContainer = SurfaceRaised, onSurfaceVariant = Muted, outline = Border,
                secondaryContainer = SurfaceRaised, onSecondaryContainer = Paper,
                surfaceVariant = Color(0xFF302B25),
            )) {
                Surface(Modifier.fillMaxSize(), color = SurfaceDark) { CameraApp() }
            }
        }
    }
}

private enum class Screen { CAMERA, GALLERY, DETAIL, SETTINGS, LICENSES }

@Composable
private fun CameraApp(model: CameraModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    var detailUri by rememberSaveable { mutableStateOf<String?>(null) }
    var detailFromGallery by rememberSaveable { mutableStateOf(false) }
    val galleryGrid = rememberLazyGridState()
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(Flash.OFF) }
    var permission by remember { mutableStateOf(hasPermission(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissionPrefs = remember(context) { context.getSharedPreferences("camera_permission", Context.MODE_PRIVATE) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requested = true
        permissionPrefs.edit().putBoolean("asked", true).apply()
        permission = it
    }
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permission = hasPermission(context)
        model.refreshPhotos()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.clearMessage()
        }
    }

    fun back() {
        screen = when (screen) {
            Screen.LICENSES -> Screen.SETTINGS
            Screen.DETAIL -> if (detailFromGallery) Screen.GALLERY else Screen.CAMERA
            else -> Screen.CAMERA
        }
    }
    fun show(photo: Photo, fromGallery: Boolean) {
        detailUri = photo.uri.toString()
        detailFromGallery = fromGallery
        screen = Screen.DETAIL
    }

    BackHandler(screen != Screen.CAMERA) { back() }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (screen) {
            Screen.CAMERA -> when {
                !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !permission -> PermissionScreen(
                    openSettings = (requested || permissionPrefs.getBoolean("asked", false)) &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, Manifest.permission.CAMERA),
                    allow = {
                        val activity = context as Activity
                        if ((requested || permissionPrefs.getBoolean("asked", false)) &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)))
                        } else permissions.launch(Manifest.permission.CAMERA)
                    },
                    gallery = { screen = Screen.GALLERY },
                )
                else -> CameraScreen(
                    model, state, front, flash,
                    onFlash = { flash = flash.next() },
                    onFlip = { front = !front },
                    onSettings = { screen = Screen.SETTINGS },
                    onPhotos = {
                        val latest = state.photos.firstOrNull()
                        if (latest == null) screen = Screen.GALLERY else show(latest, false)
                    },
                )
            }
            Screen.GALLERY -> GalleryScreen(state.photos, galleryGrid, ::back) { show(it, true) }
            Screen.DETAIL -> {
                PhotoScreen(state.photos, detailUri, model, ::back, showGallery = { screen = Screen.GALLERY },
                    fromGallery = detailFromGallery)
            }
            Screen.SETTINGS -> SettingsScreen(state.settings, model, ::back) { screen = Screen.LICENSES }
            Screen.LICENSES -> LicenseScreen(::back)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp))
    }
}

private fun hasPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionScreen(openSettings: Boolean, allow: () -> Unit, gallery: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_photo_camera), null, Modifier.size(50.dp), tint = Accent)
        Spacer(Modifier.height(24.dp))
        Text("カメラのアクセスを許可", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("ファインダーの表示と写真の撮影に、カメラへのアクセスが必要です。", color = Muted)
        Spacer(Modifier.height(28.dp))
        Button(onClick = allow) { Text(if (openSettings) "端末の設定を開く" else "カメラを許可") }
        TextButton(onClick = gallery) { Text("ギャラリー") }
    }
}

