package org.streampocket

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Opt-in test of the installed, signed release, without substituting the download transport. */
class PublishedUpgradeDeviceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(root==null) emptyList() else listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun texts()=nodes(instrument.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }.distinct().joinToString(" | ")
    private fun waitText(text:String,timeout:Long=15000):AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeout
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            nodes(instrument.uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString()==text }?.let { return it }
            android.os.SystemClock.sleep(200)
        }
        throw AssertionError("Missing $text; UI: ${texts()}")
    }
    private fun click(text:String) {
        var node:AccessibilityNodeInfo?=waitText(text)
        while(node!=null && !node.isClickable) node=node.parent
        assertTrue("Cannot click $text",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    @Test fun installedReleaseUpdatesThroughSystemConfirmation() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("publishedUpgrade") == "true")
        val previous = requireNotNull(args.getString("fromVersion"))
        val expected = requireNotNull(args.getString("toVersion"))
        val code = requireNotNull(args.getString("toCode")).toInt()
        val pkg = "org.streampocket"
        val context = instrument.targetContext
        fun identity() = context.packageManager.getPackageInfo(pkg, 0)
        val before = identity()
        assertEquals(previous, before.versionName)
        assertTrue(code > before.versionCode)
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
        context.startActivity(launch)
        click("设置")
        click("应用更新")
        waitText("下载并安装", 60000)
        assertTrue("Unexpected update: ${texts()}", texts().contains("新版 $expected ·"))
        click("下载并安装")
        val deadline = android.os.SystemClock.elapsedRealtime() + 200000
        var confirmed = false
        while (android.os.SystemClock.elapsedRealtime() < deadline && !confirmed) {
            val root = instrument.uiAutomation.rootInActiveWindow
            if (root?.packageName?.contains("packageinstaller") == true) {
                val button = nodes(root).firstOrNull { it.isClickable && it.text?.toString() in listOf("Update", "更新") }
                confirmed = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            val text = texts()
            if (text.contains("重新下载") || text.contains("检查失败")) fail("Upgrade failed: $text")
            android.os.SystemClock.sleep(200)
        }
        assertTrue("System update confirmation missing: ${texts()}", confirmed)
        val installedDeadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (identity().versionCode != code && android.os.SystemClock.elapsedRealtime() < installedDeadline) {
            android.os.SystemClock.sleep(200)
        }
        val after = identity()
        assertEquals(expected, after.versionName)
        assertEquals(code, after.versionCode)
        assertEquals(before.firstInstallTime, after.firstInstallTime)
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        click("设置")
        click("应用更新")
        waitText("Pocket Live · $expected")
    }
}
