package tk.glucodata.ui.setup

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.drivers.anytime.AnytimeRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryBarcodeDecoderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun barcode(text: String, format: BarcodeFormat = BarcodeFormat.QR_CODE): Bitmap {
        val code = MultiFormatWriter().encode(text, format, 480, 480)
        val pixels = IntArray(code.width * code.height) { i ->
            if (code[i % code.width, i / code.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, code.width, code.height, Bitmap.Config.ARGB_8888)
    }

    private fun imageUri(bitmap: Bitmap): Uri {
        val file = temporaryFolder.newFile()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test fun readsFactoryQrFromImageFile() {
        assertEquals("a61061B", GalleryBarcodeDecoder.decode(context, imageUri(barcode("a61061B"))))
    }

    @Test fun photoPickerContentUriCanBeOpenedForBoundsAndPixels() {
        val data = ByteArrayOutputStream()
        val bitmap = barcode("AB34567")
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, data))
        bitmap.recycle()
        val uri = Uri.parse("content://photo-picker-test/current-probe")
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(data.toByteArray())
        }
        assertEquals("AB34567", GalleryBarcodeDecoder.decode(context, uri))
    }

    @Test fun dataMatrixRetainsLeadingAndInternalGsSeparators() {
        val payload = "\u001D0106972831641476112512161727061510LT46251211C\u001D21P2251211237GDR75"
        assertEquals(payload, GalleryBarcodeDecoder.decode(context, imageUri(barcode(payload, BarcodeFormat.DATA_MATRIX))))
    }

    @Test fun rotatedLabelsRemainReadable() {
        for (format in listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)) {
            for (angle in listOf(90f, 180f, 270f)) {
                val original = barcode("AB34567", format)
                val rotation = Matrix().apply { postRotate(angle) }
                val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, rotation, false)
                original.recycle()
                assertEquals("rotation $angle / $format", "AB34567", GalleryBarcodeDecoder.decode(context, imageUri(rotated)))
            }
        }
    }

    @Test fun invertedQrIsReadable() {
        val normal = barcode("AB34567")
        val pixels = IntArray(normal.width * normal.height)
        normal.getPixels(pixels, 0, normal.width, 0, 0, normal.width, normal.height)
        for (i in pixels.indices) pixels[i] = if (pixels[i] == Color.BLACK) Color.WHITE else Color.BLACK
        val inverted = Bitmap.createBitmap(pixels, normal.width, normal.height, Bitmap.Config.ARGB_8888)
        normal.recycle()
        assertEquals("AB34567", GalleryBarcodeDecoder.decode(context, imageUri(inverted)))
    }

    @Test fun existingTransmitterBarcodeFormatRemainsSupported() {
        assertEquals("12345678", GalleryBarcodeDecoder.decode(context, imageUri(barcode("12345678", BarcodeFormat.CODE_128))))
    }

    @Test fun largePhotoIsDownsampledAndDecoded() {
        val original = barcode("AB34567")
        val enlarged = Bitmap.createScaledBitmap(original, 4800, 4800, false)
        original.recycle()
        assertEquals("AB34567", GalleryBarcodeDecoder.decode(context, imageUri(enlarged)))
    }

    @Test fun blankImageDoesNotReturnPayload() {
        val blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        assertNull(GalleryBarcodeDecoder.decode(context, imageUri(blank)))
    }

    @Test fun missingCorruptOrUnavailableImagesDoNotThrow() {
        val corrupt = temporaryFolder.newFile().apply { writeText("not an image") }
        assertNull(GalleryBarcodeDecoder.decode(context, Uri.fromFile(corrupt)))
        assertNull(GalleryBarcodeDecoder.decode(context, Uri.fromFile(java.io.File(temporaryFolder.root, "absent"))))
        val denied = Uri.parse("content://photo-picker-test/revoked-grant")
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(denied) {
            throw SecurityException("Grant revoked")
        }
        assertNull(GalleryBarcodeDecoder.decode(context, denied))
    }

    @Test fun decodedImageUsesCurrentProbeValidationAndPreservesHistoryOnRejection() {
        val id = "ANY:AA:BB:CC:DD:EE:FF"
        AnytimeRegistry.ensureSensorRecord(context, id, "AA:BB:CC:DD:EE:FF", "CT4")
        AnytimeRegistry.saveQrContent(context, id, "a61061B")
        AnytimeRegistry.saveLastGlucoseId(context, id, 7000)
        val invalid = GalleryBarcodeDecoder.decode(context, imageUri(barcode("unrelated-app-download")))!!
        assertFalse(AnytimeRegistry.updateCurrentProbeQr(context, id, invalid))
        assertEquals("a61061B", AnytimeRegistry.loadQrContent(context, id))
        assertEquals(7000, AnytimeRegistry.loadLastGlucoseId(context, id))
        val replacement = GalleryBarcodeDecoder.decode(context, imageUri(barcode("AB34567")))!!
        assertTrue(AnytimeRegistry.updateCurrentProbeQr(context, id, replacement))
        assertEquals("AB34567", AnytimeRegistry.loadQrContent(context, id))
        assertEquals(7000, AnytimeRegistry.loadLastGlucoseId(context, id))
    }
}
