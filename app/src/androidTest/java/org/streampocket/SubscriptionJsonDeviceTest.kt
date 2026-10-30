package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class SubscriptionJsonDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun model():AppModel {
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity)[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        ui.waitUntil(15000) { !model.state.value.loading }
        return model
    }
    private fun imported(model:AppModel,name:String) {
        ui.waitUntil(15000) { model.state.value.importing.isEmpty() && model.state.value.saved.subscriptions.any { it.name==name } }
    }
    @Test fun localFileReplaceFailurePersistenceAndPaste() {
        val model=model()
        val context=ui.activity
        val dir=java.io.File(context.cacheDir,"updates/ready").apply { mkdirs() }
        val file=java.io.File(dir,"subscription-fixture.json")
        val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        val name="JSON device ${System.nanoTime()}"
        val json="""{"name":"$name","sources":[{"name":"本地测试源","url":"https://example.org/api"}]}"""
        try {
            file.writeText("\uFEFF"+json)
            ui.runOnIdle { model.importJsonFile(uri) }
            imported(model,name)
            val sub=model.state.value.saved.subscriptions.first { it.name==name }
            assertTrue(sub.isLocalJson)
            ui.runOnIdle { model.importJsonFile(uri) }
            ui.waitUntil(10000) { model.state.value.importing.isEmpty() && model.state.value.message?.contains("订阅已存在")==true }
            assertEquals(1,model.state.value.saved.subscriptions.count { it.name==name })
            ui.runOnIdle { if(sub.sources.single().key !in model.state.value.saved.disabled) model.toggle(sub.sources.single()) }
            ui.waitUntil(5000) { sub.sources.single().key in model.state.value.saved.disabled }
            file.writeText("{bad}")
            ui.runOnIdle { model.importJsonFile(uri,sub) }
            ui.waitUntil(10000) { model.state.value.importing.isEmpty() && model.state.value.message?.contains("合法 JSON")==true }
            assertEquals(sub,model.state.value.saved.subscriptions.first { it.id==sub.id })
            file.writeBytes(ByteArray(JsonData.LIMIT+1){32})
            ui.runOnIdle { model.importJsonFile(uri,sub) }
            ui.waitUntil(10000) { model.state.value.importing.isEmpty() && model.state.value.message?.contains("2 MiB")==true }
            assertEquals(sub,model.state.value.saved.subscriptions.first { it.id==sub.id })
            file.writeText(json.replace(name,"$name updated"))
            ui.runOnIdle { model.importJsonFile(uri,sub) }
            imported(model,"$name updated")
            val updated=model.state.value.saved.subscriptions.first { it.id==sub.id }
            assertTrue(sub.sources.single().key in model.state.value.saved.disabled)
            assertEquals(updated,runBlocking { Storage(context).read() }.subscriptions.first { it.id==sub.id })
            file.delete()
            assertEquals(1,updated.sources.size)
            ui.onNodeWithText("订阅",useUnmergedTree=true).performClick()
            ui.onNodeWithContentDescription("添加订阅").performClick()
            ui.onNodeWithText("粘贴 JSON").performScrollTo().performClick()
            ui.onNodeWithTag("subscriptionJsonInput").performTextInput("{bad}")
            ui.onNodeWithText("导入",useUnmergedTree=true).performClick()
            ui.waitUntil(10000) { model.state.value.importing.isEmpty() && model.state.value.message?.contains("合法 JSON")==true }
            ui.onNodeWithTag("subscriptionJsonInput").assertTextContains("{bad}")
            ui.onNodeWithTag("subscriptionJsonInput").performTextReplacement(json.replace(name,"$name pasted"))
            ui.onNodeWithText("导入",useUnmergedTree=true).performClick()
            imported(model,"$name pasted")
            ui.runOnIdle { model.delete(sub.id);model.delete(model.state.value.saved.subscriptions.first { it.name=="$name pasted" }.id) }
            ui.waitUntil(10000) { model.state.value.saved.subscriptions.none { it.name.startsWith(name) } }
        } finally {
            file.delete()
            ui.runOnIdle { model.state.value.saved.subscriptions.filter { it.name.startsWith(name) }.forEach { model.delete(it.id) } }
            ui.waitUntil(10000) { model.state.value.saved.subscriptions.none { it.name.startsWith(name) } }
            ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        }
    }

    @Test fun systemPickerImportsJsonDocument() {
        val model=model()
        val instrument=InstrumentationRegistry.getInstrumentation()
        val resolver=ui.activity.contentResolver
        val name="pocket-fixture-${System.nanoTime()}.json"
        val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
        })!!
        resolver.openOutputStream(uri)!!.use { it.write("""{"name":"$name","sources":[{"url":"https://example.org/picker"}]}""".toByteArray()) }
        fun nodes(root:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
            if(root==null) emptyList() else listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        try {
            ui.onNodeWithText("订阅",useUnmergedTree=true).performClick()
            if(model.state.value.saved.subscriptions.isNotEmpty()) ui.onNodeWithContentDescription("添加订阅").performClick()
            ui.onNodeWithText("选择 JSON 文件").performScrollTo().performClick()
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            var clicked=false
            while(!clicked && android.os.SystemClock.elapsedRealtime()<deadline) {
                val all=nodes(instrument.uiAutomation.rootInActiveWindow)
                var node=all.firstOrNull { it.text?.toString()==name }
                if(node!=null) {
                    while(node!=null && !node.isClickable) node=node.parent
                    clicked=node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
                }
                if(!clicked) android.os.SystemClock.sleep(200)
            }
            assertTrue("File missing from picker: "+nodes(instrument.uiAutomation.rootInActiveWindow).mapNotNull { it.text }.joinToString(),clicked)
            imported(model,name)
            val sub=model.state.value.saved.subscriptions.first { it.name==name }
            ui.onNodeWithTag("subscriptionsList").performScrollToKey("subscription:${sub.id}")
            ui.onNodeWithTag("subscription:${sub.id}").assertExists()
            val output=java.io.File(ui.activity.getExternalFilesDir(null),"json-import.png")
            instrument.uiAutomation.takeScreenshot().let { bitmap -> output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            ui.runOnIdle { model.delete(sub.id) }
            ui.waitUntil(10000) { model.state.value.saved.subscriptions.none { it.id==sub.id } }
        } finally {
            resolver.delete(uri,null,null)
            ui.runOnIdle { model.state.value.saved.subscriptions.filter { it.name==name }.forEach { model.delete(it.id) } }
            ui.waitUntil(10000) { model.state.value.saved.subscriptions.none { it.name==name } }
            ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        }
    }
}
