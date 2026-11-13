package jp.noppy.lookcamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class Photo(val uri: Uri, val timestamp: Long, val width: Int, val height: Int)

internal class PhotoLibrary(private val context: Context) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun save(raw: File, look: LookSelection, quality: Int, timestamp: Long): Photo {
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val (width, height) = info.size.run { width to height }
            val pixels = width.toDouble() * height
            val scale = minOf(1.0, 4096.0 / maxOf(width, height), sqrt(12_600_000.0 / pixels))
            if (scale < 1) {
                decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
            }
        }
        val encoded = File.createTempFile("graded-", ".jpg", context.cacheDir)
        try {
            val graded = if (look.amount == 0f) source else BitmapLookRenderer().use { it.apply(source, look) }
            try {
                encoded.outputStream().buffered().use { output ->
                    if (!graded.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("JPEG encoding failed")
                    }
                }
            } finally {
                if (graded !== source) graded.recycle()
            }
            ExifInterface(encoded).apply {
                setAttribute(ExifInterface.TAG_USER_COMMENT, look.metadata)
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                val exifDate = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(timestamp))
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                saveAttributes()
            }
            return publish(encoded, timestamp, source.width, source.height)
        } finally {
            source.recycle()
            encoded.delete()
        }
    }

    private fun publish(file: File, timestamp: Long, width: Int, height: Int): Photo {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LOOK_${timestamp}_${UUID.randomUUID().toString().take(8)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            val destination = resolver.openOutputStream(uri) ?: throw IOException("Cannot open JPEG destination")
            destination.use { output -> file.inputStream().use { it.copyTo(output) } }
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, ready, null, null) != 1) throw IOException("MediaStore publish failed")
            return Photo(uri, timestamp, width, height)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun list(): List<Photo> {
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ALBUM))
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_TAKEN))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        return resolver.query(collection, columns, query, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Photo(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                        cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)))
                }
            }
        }.orEmpty()
    }

    fun look(photo: Photo): LookSelection {
        val metadata = resolver.openInputStream(photo.uri)?.use {
            ExifInterface(it).getAttribute(ExifInterface.TAG_USER_COMMENT)
        }
        return LookSelection.fromMetadata(metadata)
    }

    companion object {
        const val ALBUM = "DCIM/Look Camera/"
    }
}

