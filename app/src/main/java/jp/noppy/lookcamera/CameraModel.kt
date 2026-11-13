package jp.noppy.lookcamera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Application.cameraPreferences by preferencesDataStore(name = "camera_settings")

internal enum class Framing(val label: String, val width: Int, val height: Int) {
    FOUR_THREE("4:3", 4, 3), SQUARE("1:1", 1, 1), WIDE("16:9", 16, 9)
}

internal data class CameraSettings(
    val quality: Int = 95,
    val framing: Framing = Framing.FOUR_THREE,
    val restoreLook: Boolean = true,
    val showGrid: Boolean = false,
    val favorites: Set<String> = emptySet(),
)

internal const val MAX_PENDING_PHOTOS = 3

internal data class CameraUiState(
    val ready: Boolean = false,
    val settings: CameraSettings = CameraSettings(),
    val framing: Framing = Framing.FOUR_THREE,
    val look: LookSelection = LookSelection(Looks.off, 0f),
    val capturing: Boolean = false,
    val pendingPhotos: Int = 0,
    val photos: List<Photo> = emptyList(),
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val message: String? = null,
) {
    val canCapture: Boolean get() = !capturing && pendingPhotos < MAX_PENDING_PHOTOS
}

internal class CameraModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = app.cameraPreferences
    private val library = PhotoLibrary(app)
    private val main = ContextCompat.getMainExecutor(app)
    private val mutable = MutableStateFlow(CameraUiState())
    val state = mutable.asStateFlow()
    private var photoRevision = 0
    private val photoWriter = Mutex()

    init {
        viewModelScope.launch {
            val prefs = settingsStore.data.catch { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Settings read failed", error)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }.first()
            val settings = CameraSettings(
                quality = prefs[QUALITY]?.coerceIn(70, 100) ?: 95,
                framing = Framing.entries.firstOrNull { it.name == prefs[FRAMING] } ?: Framing.FOUR_THREE,
                restoreLook = prefs[RESTORE] ?: true,
                showGrid = prefs[GRID] ?: false,
                favorites = prefs[FAVORITES].orEmpty().split(',').filter { Looks.byId(it) != Looks.off }.toSet(),
            )
            val preset = if (settings.restoreLook) {
                Looks.byId(prefs[LAST_LOOK] ?: "film_golden_negative")
            } else Looks.off
            val selection = LookSelection(preset, if (preset == Looks.off) 0f else prefs[STRENGTH] ?: preset.defaultIntensity)
            mutable.update { it.copy(ready = true, settings = settings, framing = settings.framing, look = selection) }
        }
        refreshPhotos()
        viewModelScope.launch {
            try {
                val thumbnails = withContext(Dispatchers.Default) {
                    val source = app.assets.open("look_sample.jpg").use { BitmapFactory.decodeStream(it) }
                    BitmapLookRenderer().use { renderer ->
                        Looks.all.associate { preset ->
                            preset.id to renderer.apply(source, LookSelection(preset, preset.defaultIntensity))
                        }
                    }
                }
                mutable.update { it.copy(thumbnails = thumbnails) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Sample rendering failed", error)
            }
        }
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }
    fun notify(message: String) = mutable.update { it.copy(message = message) }
    fun setFraming(framing: Framing) = mutable.update { it.copy(framing = framing) }

    fun select(preset: FilterPreset) {
        if (state.value.look.preset != preset) setLook(LookSelection(preset, preset.defaultIntensity))
    }

    fun setIntensity(intensity: Float) = mutable.update { it.copy(look = it.look.copy(intensity = intensity)) }

    fun saveIntensity() = persistLook(state.value.look)

    private fun setLook(look: LookSelection) {
        mutable.update { it.copy(look = look) }
        persistLook(look)
    }

    private fun persistLook(look: LookSelection) = persist {
        it[LAST_LOOK] = look.preset.id
        it[STRENGTH] = look.amount
    }

    fun setQuality(quality: Int) {
        mutable.update { it.copy(settings = it.settings.copy(quality = quality)) }
        persist { it[QUALITY] = quality }
    }

    fun setDefaultFraming(framing: Framing) {
        mutable.update { it.copy(framing = framing, settings = it.settings.copy(framing = framing)) }
        persist { it[FRAMING] = framing.name }
    }

    fun setRestore(restore: Boolean) {
        mutable.update { it.copy(settings = it.settings.copy(restoreLook = restore)) }
        persist { it[RESTORE] = restore }
    }

    fun setGrid(show: Boolean) {
        mutable.update { it.copy(settings = it.settings.copy(showGrid = show)) }
        persist { it[GRID] = show }
    }

    fun toggleFavorite(preset: FilterPreset) {
        if (preset == Looks.off) return
        val current = state.value.settings.favorites
        val updated = if (preset.id in current) current - preset.id else current + preset.id
        mutable.update { it.copy(settings = it.settings.copy(favorites = updated)) }
        persist { it[FAVORITES] = updated.joinToString(",") }
    }

    private fun persist(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch {
            try {
                settingsStore.edit { change(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Settings write failed", error)
                notify("設定を保存できませんでした")
            }
        }
    }

    fun refreshPhotos() {
        val revision = ++photoRevision
        viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) { library.list() }
                if (revision == photoRevision) mutable.update { it.copy(photos = photos) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Gallery query failed", error)
                notify("写真の一覧を読み込めませんでした")
            }
        }
    }

    suspend fun readLook(photo: Photo): LookSelection? = withContext(Dispatchers.IO) {
        try {
            library.look(photo)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    fun shoot(capture: ImageCapture, mirror: Boolean): Boolean {
        val snapshot = state.value
        if (!snapshot.canCapture) return false
        val raw = try {
            File.createTempFile("capture-", ".jpg", getApplication<Application>().cacheDir)
        } catch (error: Exception) {
            reportCaptureError(error)
            return false
        }
        val start = SystemClock.elapsedRealtime()
        val timestamp = System.currentTimeMillis()
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirror }
        val output = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
        mutable.update { it.copy(capturing = true, pendingPhotos = it.pendingPhotos + 1) }
        try {
            capture.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    mutable.update { it.copy(capturing = false) }
                    viewModelScope.launch {
                        try {
                            val photo = photoWriter.withLock {
                                withContext(Dispatchers.IO + NonCancellable) {
                                    library.save(raw, snapshot.look, snapshot.settings.quality, timestamp)
                                }
                            }
                            ++photoRevision
                            mutable.update { it.copy(photos = listOf(photo) + it.photos.filterNot { other -> other.uri == photo.uri }) }
                            Log.i(TAG, "JPEG saved in ${SystemClock.elapsedRealtime() - start} ms (${photo.width}x${photo.height})")
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            reportCaptureError(error)
                        } finally {
                            mutable.update { it.copy(pendingPhotos = it.pendingPhotos - 1) }
                        }
                    }.invokeOnCompletion { raw.delete() }
                }

                override fun onError(exception: ImageCaptureException) {
                    raw.delete()
                    captureFailed(exception)
                }
            })
            return true
        } catch (error: Exception) {
            raw.delete()
            captureFailed(error)
            return false
        }
    }

    private fun captureFailed(error: Throwable) {
        mutable.update { it.copy(capturing = false, pendingPhotos = it.pendingPhotos - 1) }
        reportCaptureError(error)
    }

    private fun reportCaptureError(error: Throwable) {
        Log.e(TAG, "Capture or save failed", error)
        notify("写真を保存できませんでした。もう一度撮影してください")
    }

    companion object {
        private const val TAG = "LookCamera"
        private val QUALITY = intPreferencesKey("jpeg_quality")
        private val FRAMING = stringPreferencesKey("default_framing")
        private val RESTORE = booleanPreferencesKey("restore_look")
        private val GRID = booleanPreferencesKey("show_grid")
        private val FAVORITES = stringPreferencesKey("favorite_looks")
        private val LAST_LOOK = stringPreferencesKey("last_look")
        private val STRENGTH = floatPreferencesKey("last_strength")
    }
}

