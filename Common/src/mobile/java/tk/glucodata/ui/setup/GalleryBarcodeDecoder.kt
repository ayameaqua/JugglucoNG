package tk.glucodata.ui.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.PhotoScan
import kotlin.math.max

/** Shared by embedded scanners, transmitter setup and the full-screen scanner. */
suspend fun decodeBitmapQr(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) { GalleryBarcodeDecoder.decode(context, uri) }

object GalleryBarcodeDecoder {
    private const val MAX_EDGE = 4096
    private const val MAX_PIXELS = 8_000_000L

    /** Blocking image IO/decoding: callers must use a worker thread. No URI/QR payload is logged. */
    @JvmStatic
    fun decode(context: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null

            var sample = 1
            while (max(width, height) / sample > 1600) sample *= 2
            // Try a small image first, then more detail for dense labels, within a memory budget.
            val attempts = linkedSetOf(sample, max(1, sample / 2), 1)
            for (sampleSize in attempts) {
                val sampledWidth = (width.toLong() + sampleSize - 1) / sampleSize
                val sampledHeight = (height.toLong() + sampleSize - 1) / sampleSize
                if (max(sampledWidth, sampledHeight) > MAX_EDGE ||
                    sampledWidth * sampledHeight > MAX_PIXELS
                ) continue
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: continue
                try {
                    decodeBitmap(bitmap)?.let { return it }
                } finally {
                    bitmap.recycle()
                }
            }
            null
        } catch (_: Exception) {
            // Cancelled/revoked picker grants, corrupt images and missing codes leave setup intact.
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    internal fun decodeBitmap(bitmap: Bitmap): String? {
        if (bitmap.width.toLong() * bitmap.height > MAX_PIXELS ||
            max(bitmap.width, bitmap.height) > MAX_EDGE
        ) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // A tightly cropped Data Matrix may touch the photo edges. Supply a white quiet zone
        // without allocating another bitmap, so its border detector can still find the label.
        val luma = RGBLuminanceSource(bitmap.width, bitmap.height, pixels).matrix
        val paddedWidth = bitmap.width + 64
        val paddedHeight = bitmap.height + 64
        val padded = ByteArray(paddedWidth * paddedHeight) { 0xff.toByte() }
        for (y in 0 until bitmap.height) {
            System.arraycopy(luma, y * bitmap.width, padded, (y + 32) * paddedWidth + 32, bitmap.width)
        }
        var source: LuminanceSource = PlanarYUVLuminanceSource(
            padded, paddedWidth, paddedHeight, 0, 0, paddedWidth, paddedHeight, false
        )
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.ALSO_INVERTED to true)
        val reader = MultiFormatReader()
        repeat(4) { rotation ->
            try {
                // Preserve all barcode formats supported by the existing Sibionics gallery path.
                val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
                PhotoScan.trimOuterScannerWhitespace(text)?.takeIf { it.isNotEmpty() }?.let { return it }
            } catch (_: Exception) {
                // Some Data Matrix labels need an explicit rotation, even with TRY_HARDER.
            } finally {
                reader.reset()
            }
            if (rotation < 3) {
                val width = source.width
                val height = source.height
                val original = source.matrix
                val rotated = ByteArray(original.size)
                for (y in 0 until height) for (x in 0 until width) {
                    rotated[(width - 1 - x) * height + y] = original[y * width + x]
                }
                source = PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false)
            }
        }
        return null
    }
}
