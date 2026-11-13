package jp.noppy.lookcamera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoPipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shaderChangesColorAndKeepsTheTopAndBottomInPlace() {
        val source = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val pattern = IntArray(48 * 48) {
            if (it / 48 < 24) Color.rgb(178, 112, 65) else Color.rgb(16, 35, 68)
        }
        source.setPixels(pattern, 0, 48, 0, 0, 48, 48)
        try {
            BitmapLookRenderer().use { renderer ->
                val silver = Looks.byId("film_silver_grain")
                assertSame(source, renderer.apply(source, LookSelection(silver, 0f)))
                val graded = renderer.apply(source, LookSelection(silver, 1f))
                try {
                    val top = graded.getPixel(24, 8)
                    val bottom = graded.getPixel(24, 40)
                    assertEquals(Color.red(top), Color.blue(top))
                    assertTrue(Color.red(top) > Color.red(bottom) + 50)
                    assertNotEquals(source.getPixel(24, 8), top)
                } finally {
                    graded.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun halationWarmsTheDarkSideOfABrightEdge() {
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val colors = IntArray(512 * 512) { if (it % 512 >= 256) Color.WHITE else Color.rgb(70, 70, 70) }
        source.setPixels(colors, 0, 512, 0, 0, 512, 512)
        val stock = FilterPreset("test_halation", "Halation", Category.FILM, Grade(halation = .7f))
        try {
            BitmapLookRenderer().use { renderer ->
                val graded = renderer.apply(source, LookSelection(stock, 1f))
                try {
                    val rim = graded.getPixel(254, 256)
                    val shade = graded.getPixel(100, 256)
                    assertTrue(Color.red(rim) > Color.blue(rim) + 3)
                    assertEquals(Color.red(shade), Color.blue(shade))
                } finally {
                    graded.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun rotatedCaptureIsPublishedWithUprightPixelsAndSnapshotMetadata() {
        val library = PhotoLibrary(context)
        val raw = File.createTempFile("test-rotation-", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        val colors = IntArray(120 * 90) { if (it % 120 < 60) Color.RED else Color.BLUE }
        source.setPixels(colors, 0, 120, 0, 0, 120, 90)
        var saved: Photo? = null
        try {
            raw.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(raw).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val selection = LookSelection(Looks.byId("film_golden_negative"), 0.42f)
            val photo = library.save(raw, selection, 95, System.currentTimeMillis()).also { saved = it }
            assertEquals(90, photo.width)
            assertEquals(120, photo.height)
            assertEquals(selection, library.look(photo))
            assertTrue(library.list().any { it.uri == photo.uri })
            val jpeg = context.contentResolver.openFileDescriptor(photo.uri, "r")!!
            jpeg.use {
                val decoded = android.graphics.BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                try {
                    val top = decoded.getPixel(45, 20)
                    val bottom = decoded.getPixel(45, 100)
                    assertTrue(Color.red(top) > Color.blue(top) + 120)
                    assertTrue(Color.blue(bottom) > Color.red(bottom) + 120)
                    assertEquals(90, decoded.width)
                    assertEquals(120, decoded.height)
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            saved?.let { context.contentResolver.delete(it.uri, null, null) }
            raw.delete()
            source.recycle()
        }
    }
}

