package tk.glucodata

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class UnifiedScanGalleryTest {
    @Test fun recreatingActivityDuringPickerRetainsPendingSelectionUntilResultReturns() {
        val first = Robolectric.buildActivity(UnifiedScanActivity::class.java)
        first.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        first.create().start().resume()
        first.get().findViewById<Button>(R.id.scanGalleryButton).performClick()
        val request = shadowOf(first.get()).nextStartedActivityForResult
        val saved = Bundle()
        first.pause().stop().saveInstanceState(saved).destroy()
        val recreated = Robolectric.buildActivity(UnifiedScanActivity::class.java)
        recreated.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        try {
            recreated.create(saved).start().resume()
            val button = recreated.get().findViewById<Button>(R.id.scanGalleryButton)
            assertFalse(button.isEnabled)
            assertTrue(recreated.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
            assertTrue(button.isEnabled)
            assertFalse(recreated.get().isFinishing)
        } finally {
            recreated.pause().stop().destroy()
        }
    }

    @Test fun denyingCameraStillAllowsPickingAnImageAndCancellingKeepsScannerOpen() {
        val controller = Robolectric.buildActivity(UnifiedScanActivity::class.java)
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        try {
            controller.create().start().resume()
            activity.onRequestPermissionsResult(0x541, arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_DENIED))
            assertFalse(activity.isFinishing)
            val button = activity.findViewById<Button>(R.id.scanGalleryButton)
            assertTrue(button.isEnabled)
            assertTrue(button.performClick())
            val picker = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(picker)
            assertFalse(button.isEnabled)
            assertTrue(activity.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_CANCELED, null))
            assertTrue(button.isEnabled)
            assertFalse(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
