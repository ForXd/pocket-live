package org.streampocket

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class GlassBackdropDeviceTest {
    @Test fun blurIsBoundedAndLeavesCachedArtworkUntouched() {
        val source=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(1000*600) { if(it%1000<500) Color.WHITE else Color.BLACK }
        source.setPixels(pixels,0,1000,0,0,1000,600)
        val blurred=blurGlassBitmap(source)
        try {
            assertTrue(blurred.width<=320 && blurred.height<=320)
            assertTrue(blurred.allocationByteCount<=320*320*4)
            assertEquals(Color.WHITE,source.getPixel(499,300))
            assertEquals(Color.BLACK,source.getPixel(500,300))
            val edge=Color.red(blurred.getPixel(blurred.width/2,blurred.height/2))
            assertTrue("Backdrop edge must visibly diffuse",edge in 40..210)
            assertFalse(source.isRecycled)
            assertNotSame(source,blurred)
        } finally { blurred.recycle();source.recycle() }
    }
}
