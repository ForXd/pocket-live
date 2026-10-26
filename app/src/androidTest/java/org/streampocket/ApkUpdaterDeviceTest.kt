package org.streampocket

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApkUpdaterDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun release(bytes:ByteArray)=AppRelease("9.0.0","https://github.com/ForXd/pocket-live/releases/download/v9.0.0/pocket-live-9.0.0-release.apk","https://github.com/ForXd/pocket-live/releases/tag/v9.0.0","测试应用内更新",bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun shell(command:String) { instrument.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    @Test fun downloadVerifyRestorePermissionAndInstallerUseContentUri() {
        val bytes=instrument.context.assets.open("update-fixture.apk").use { it.readBytes() }
        val server=MockWebServer();server.start()
        // Only this test transport rewrites the already-validated official URL to a local fixture.
        val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/update.apk")).build()) }.build()
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var model:AppModel
        ui.runOnUiThread { ui.activity.viewModelStore.clear();model=ViewModelProvider(ui.activity)[AppModel::class.java] }
        File(ui.activity.cacheDir,"updates").deleteRecursively()
        var updater=ApkUpdater(ui.activity.application,client,scope)
        val release=release(bytes)
        try {
            ui.waitUntil(5000) { scope.coroutineContext[Job]!!.children.none() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            updater.download(release)
            ui.waitUntil(10000) { updater.state.value.phase in listOf(UpdatePhase.READY,UpdatePhase.ERROR) }
            assertEquals(updater.state.value.error,UpdatePhase.READY,updater.state.value.phase)
            assertEquals(1,server.requestCount)
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            updater=ApkUpdater(ui.activity.application,client,scope)
            ui.waitUntil(5000) { updater.state.value.phase==UpdatePhase.READY }
            val intent=runBlocking { updater.installIntent() }
            assertEquals("content",intent.data!!.scheme)
            assertEquals("${ui.activity.packageName}.updates",intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive",intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val actual=ui.activity.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }
            assertArrayEquals(bytes,actual)
            val launched=AtomicReference<Intent>()
            val monitor=object:android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent:Intent):android.app.Instrumentation.ActivityResult? {
                    if(intent.action !in listOf(Intent.ACTION_VIEW,android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) return null
                    launched.set(intent)
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
                }
            }
            instrument.addMonitor(monitor)
            try {
                shell("appops set ${ui.activity.packageName} REQUEST_INSTALL_PACKAGES deny")
                ui.runOnUiThread { ui.activity.setContent { PocketTheme { UpdateDialog(UiState(appRelease=release),model,updater,{}) } } }
                ui.onNodeWithText("安装更新").performClick()
                ui.waitUntil(5000) { launched.get()!=null }
                assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,launched.get().action)
                ui.waitUntil(5000) { updater.state.value.error?.contains("尚未允许")==true }
                shell("appops set ${ui.activity.packageName} REQUEST_INSTALL_PACKAGES allow")
                launched.set(null)
                ui.onNodeWithText("安装更新").performClick()
                ui.waitUntil(5000) { launched.get()!=null }
                assertEquals("content",launched.get().data!!.scheme)
                assertEquals("application/vnd.android.package-archive",launched.get().type)
                captureUi(ui,"verified-update")
            } finally { instrument.removeMonitor(monitor) }
            if(InstrumentationRegistry.getArguments().getString("actualInstaller")=="true") {
                ui.runOnUiThread { ui.activity.startActivity(intent) }
                android.os.SystemClock.sleep(1500)
                val root=instrument.uiAutomation.rootInActiveWindow
                assertTrue("Expected Android installer, got ${root?.packageName}", root?.packageName?.contains("packageinstaller")==true)
                val output=File(ui.activity.getExternalFilesDir(null),"actual-installer.png")
                instrument.uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                shell("input keyevent 4")
                ui.waitForIdle()
            }
            // Tampering after permission/settings return is caught before the installer sees a URI.
            File(ui.activity.cacheDir,"updates/ready/update.apk").appendText("tampered")
            try { runBlocking { updater.installIntent() };fail("Modified APK accepted") } catch(_:UserError) {}
            assertEquals(UpdatePhase.ERROR,updater.state.value.phase)
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            ui.runOnUiThread { ui.activity.viewModelStore.clear() }
            File(ui.activity.cacheDir,"updates").deleteRecursively()
            client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            server.shutdown() // Restore app-op outside instrumentation: Android kills the UID on revocation.
        }
    }
    @Test fun failedAndCancelledDownloadsAreBoundedAndLeaveNoPartialFiles() {
        val bytes=ByteArray(500_000) { 7 }
        val server=MockWebServer();server.start()
        val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/update.apk")).build()) }.build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        File(ui.activity.cacheDir,"updates").deleteRecursively()
        val updater=ApkUpdater(ui.activity.application,client,scope)
        try {
            ui.waitUntil(5000) { scope.coroutineContext[Job]!!.children.none() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024,100,TimeUnit.MILLISECONDS))
            updater.download(release(bytes))
            ui.waitUntil(5000) { updater.state.value.received>0 }
            updater.cancel()
            ui.waitUntil(5000) { scope.coroutineContext[Job]!!.children.none() && client.dispatcher.runningCallsCount()==0 }
            assertEquals(UpdatePhase.IDLE,updater.state.value.phase)
            assertTrue(File(ui.activity.cacheDir,"updates").walkTopDown().none { it.isFile && it.name.endsWith(".part") })
            server.enqueue(MockResponse().setBody("abc"))
            updater.download(release("abc".toByteArray()).copy(sha256="0".repeat(64)))
            ui.waitUntil(5000) { updater.state.value.phase==UpdatePhase.ERROR && scope.coroutineContext[Job]!!.children.none() }
            assertTrue(updater.state.value.error!!.contains("校验失败"))
            assertFalse(File(ui.activity.cacheDir,"updates/ready/update.apk").exists())
            assertEquals(0,client.dispatcher.runningCallsCount())
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();server.shutdown()
            File(ui.activity.cacheDir,"updates").deleteRecursively()
        }
    }
}
