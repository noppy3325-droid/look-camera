package jp.noppy.lookcamera

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DeviceCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val library = PhotoLibrary(context)
    // The virtual camera has smaller output than the Xiaomi validation device.
    private val virtualCamera = InstrumentationRegistry.getArguments().getString("virtualCamera") == "true"

    @Test fun tenShuttersPublishTenFullSizePhotosWithTheirOwnLook() {
        val existing = library.list().map { it.uri }.toSet()
        val created = mutableListOf<Photo>()
        openCamera()
        try {
            chooseFourThree()
            tapDescription("フィルムを選ぶ")
            for (index in 0 until 10) {
                val category = if (index % 2 == 0) "Film Stocks" else "Creative Looks"
                val look = if (index % 2 == 0) "GOLDEN NEGATIVE" else "CCD FLASH"
                tapText(category)
                tapDescription(look)
                tapDescription("シャッター")
                tapDescription("OFF")
                waitFor("JPEG ${index + 1}", 25_000) {
                    val latest = library.list().filter { it.uri !in existing }
                    if (latest.size > created.size) {
                        created += latest.first()
                        true
                    } else false
                }
                val saved = created.last()
                assertEquals(look, library.look(saved).preset.name)
                assertEquals(1f, library.look(saved).amount, 0.001f)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(saved.uri)!!.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val minimum = if (virtualCamera) 720 else 2000
                assertTrue("Unexpected JPEG dimensions: ${bounds.outWidth}×${bounds.outHeight}",
                    minOf(bounds.outWidth, bounds.outHeight) >= minimum)
                val aspect = minOf(bounds.outWidth, bounds.outHeight).toFloat() / maxOf(bounds.outWidth, bounds.outHeight)
                assertTrue("4:3 crop", abs(aspect - .75f) < .015f)
            }
            assertEquals(10, library.list().count { it.uri !in existing })
            tapDescription("フィルム選択を閉じる")
            tapDescription("直近の写真")
            waitFor("保存した写真", 6_000) { find { it.contentDescription?.toString() == "保存した写真" } != null }
            waitFor("撮影時の Look", 6_000) { find { it.text?.toString() == "CCD FLASH" } != null }
            if (virtualCamera) {
                val device = UiDevice.getInstance(instrumentation)
                val w = device.displayWidth
                val h = device.displayHeight
                device.swipe(w * 4 / 5, h / 2, w / 5, h / 2, 20)
                waitFor("前の写真の Look", 15_000) { find { it.text?.toString() == "GOLDEN NEGATIVE" } != null }
            }
            tapDescription("ギャラリー")
            waitFor("ギャラリー", 6_000) { find { it.contentDescription?.startsWith("撮影した写真") == true } != null }
        } finally {
            created.forEach {
                context.contentResolver.delete(it.uri, null, null)
            }
        }
    }

    @Test fun canSwitchToFrontCameraAndSaveSquareJpeg() {
        val existing = library.list().map { it.uri }.toSet()
        var captured: Photo? = null
        openCamera()
        try {
            chooseFourThree()
            tapDescription("露出補正")
            waitFor("露出補正ダイヤル", 20_000) {
                find { it.text?.toString()?.endsWith(" EV") == true } != null
            }
            tapDescription("前面・背面カメラ切替")
            tapDescription("アスペクト比 4:3")
            tapText("1:1")
            waitFor("1:1", 6_000) { find { it.contentDescription?.toString() == "アスペクト比 1:1" } != null }
            tapDescription("フィルムを選ぶ")
            tapDescription("OFF")
            tapDescription("フィルム選択を閉じる")
            tapDescription("シャッター")
            waitFor("前面カメラの写真", 25_000) { library.list().any { it.uri !in existing } }
            val photo = library.list().first { it.uri !in existing }.also { captured = it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(photo.uri)!!.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val minimum = if (virtualCamera) 480 else 1600
            assertTrue("Unexpected square dimensions: ${bounds.outWidth}×${bounds.outHeight}",
                bounds.outWidth >= minimum && bounds.outWidth == bounds.outHeight)
            assertEquals(Looks.off, library.look(photo).preset)
            tapDescription("設定")
            waitFor("設定画面", 12_000) { find { it.text?.toString() == "JPEG 保存品質" } != null }
            fun gridSwitch() = find {
                it.isCheckable && (it.contentDescription?.toString() == "構図グリッド" ||
                    (0 until it.childCount).any { index ->
                        it.getChild(index)?.contentDescription?.toString() == "構図グリッド"
                    })
            }
            val gridBefore = checkNotNull(gridSwitch()).isChecked
            try {
                tapDescription("構図グリッド")
                waitFor("構図グリッド切替", 6_000) {
                    gridSwitch()?.isChecked == !gridBefore
                }
            } finally {
                if (gridSwitch()?.isChecked == !gridBefore) tapDescription("構図グリッド")
            }
            tapDescription("戻る")
            waitFor("撮影画面へ戻る", 20_000) {
                find { it.contentDescription?.toString() == "シャッター" } != null
            }
        } finally {
            captured?.let {
                context.contentResolver.delete(it.uri, null, null)
            }
        }
    }

    private fun openCamera() {
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
    }

    private fun chooseFourThree() {
        waitFor("アスペクト比", 35_000) { find { it.contentDescription?.startsWith("アスペクト比 ") == true } != null }
        val current = find { it.contentDescription?.startsWith("アスペクト比 ") == true }
            ?.contentDescription?.toString()
        if (current != null && current != "アスペクト比 4:3") {
            tapDescription(current)
            tapText("4:3")
        }
    }

    private fun tapText(text: String) = tap(text) { it.text?.toString() == text }

    private fun tapDescription(description: String) = tap(description) { it.contentDescription?.toString() == description }

    private fun tap(label: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        waitFor(label, 15_000) {
            var node = find(matches)
            while (node != null) {
                if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@waitFor true
                }
                node = node.parent
            }
            false
        }
    }

    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child)?.let { return it }
            }
            return null
        }
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(label: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(125)
        }
        val root = automation.rootInActiveWindow
        val nodes = mutableListOf<String>()
        fun summarize(node: AccessibilityNodeInfo) {
            val name = node.contentDescription ?: node.text
            if (name != null) nodes += "$name (clickable=${node.isClickable})"
            for (index in 0 until node.childCount) node.getChild(index)?.let(::summarize)
        }
        root?.let(::summarize)
        fail("Timed out waiting for $label. Window=${root?.packageName}, nodes=$nodes")
    }
}

