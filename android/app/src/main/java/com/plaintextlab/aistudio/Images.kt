package com.plaintextlab.aistudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** A picture to edit: a preview for the screen and a JPEG to upload. */
class PickedImage(val preview: Bitmap, val jpeg: ByteArray)

object Images {
    /** Loads a picture the user picked, at most about 2 megapixels (ComfyUI scales to 1). */
    fun load(context: Context, uri: Uri): PickedImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val pixels = info.size.width.toDouble() * info.size.height
            val scale = minOf(1.0, sqrt(2_000_000 / pixels))
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        return PickedImage(bitmap, jpeg)
    }

    fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Not an image")

    /** Saves a PNG into Pictures/AI Studio (no permission needed on Android 10+). */
    fun saveToGallery(context: Context, png: ByteArray, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AI Studio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: error("Could not write the file")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
