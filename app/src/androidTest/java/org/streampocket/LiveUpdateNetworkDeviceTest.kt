package org.streampocket
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in real GitHub traffic: validates published APK against the installed production identity. */
class LiveUpdateNetworkDeviceTest {
    @Test fun officialReleaseDownloadsThroughSystemDnsAndValidatesInstalledSigner()=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdate")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val network=UpdateNetwork()
        val file=File(instrument.targetContext.cacheDir,"updates/ready/update.apk").also { it.parentFile!!.mkdirs() }
        try {
            val release=AppUpdates.parse(network.latest(),"0.9.0")!!
            AppUpdates.validateDownload(release)
            withContext(Dispatchers.IO) {
                network.client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                    assertTrue("HTTP ${response.code}",response.isSuccessful)
                    val digest=file.outputStream().use { output -> response.body!!.byteStream().use { input -> copyUpdate(input,output,release.bytes) } }
                    assertEquals(release.sha256,digest)
                }
                val installed=apkIdentity(instrument.targetContext.createPackageContext("org.streampocket",0))
                validateUpdateIdentity(apkIdentity(instrument.targetContext,file),installed,release)
                if(InstrumentationRegistry.getArguments().getString("completeProductionUpgrade")=="true") {
                    val context=instrument.targetContext
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    fun nodes(root:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> = if(root==null) emptyList() else listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
                    val deadline=android.os.SystemClock.elapsedRealtime()+15000
                    var clicked=false
                    while(android.os.SystemClock.elapsedRealtime()<deadline && !clicked) {
                        val root=instrument.uiAutomation.rootInActiveWindow
                        if(root?.packageName?.contains("packageinstaller")==true) {
                            val button=nodes(root).firstOrNull { it.text?.toString() in listOf("Update","更新") && it.isClickable }
                            if(button!=null) clicked=button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        android.os.SystemClock.sleep(200)
                    }
                    assertTrue("System update button not found",clicked)
                    val installedDeadline=android.os.SystemClock.elapsedRealtime()+30000
                    while(apkIdentity(context.createPackageContext("org.streampocket",0)).version!=release.version && android.os.SystemClock.elapsedRealtime()<installedDeadline) android.os.SystemClock.sleep(250)
                    assertEquals(release.version,apkIdentity(context.createPackageContext("org.streampocket",0)).version)
                }
                instrument.sendStatus(0,android.os.Bundle().apply { putString("live_update_result","${installed.version} -> ${release.version}; bytes=${file.length()}; SHA-256 and installed signer match") })
            }
        } finally { file.delete();network.close() }
    }
}
