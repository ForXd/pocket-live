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
    @Test fun installedReleaseDownloadsAndReachesSystemInstaller() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("publishedUpgrade")=="true")
        val context=instrument.targetContext
        val launch=context.packageManager.getLaunchIntentForPackage("org.streampocket") ?: android.content.Intent().setClassName("org.streampocket","org.streampocket.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        click("更新")
        waitText("下载并安装",30000)
        click("下载并安装")
        val deadline=android.os.SystemClock.elapsedRealtime()+200000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            val root=instrument.uiAutomation.rootInActiveWindow
            if(root?.packageName?.contains("packageinstaller")==true) {
                instrument.sendStatus(0,android.os.Bundle().apply { putString("release_upgrade_ui",texts()) })
                return
            }
            val text=texts()
            if(text.contains("重新下载") || text.contains("检查失败")) throw AssertionError("Release upgrade failed: $text")
            android.os.SystemClock.sleep(250)
        }
        fail("Installer not reached: ${texts()}")
    }
}
