package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTargetTest {

    @Test
    fun prefersRealMediaStoreOutputOverCacheCopy() {
        val uri = "content://media/external/audio/media/42"
        assertEquals(uri, ShareTarget.preferredOutputUri(uri))
    }

    @Test
    fun prefersRealSafCustomFolderOutputOverCacheCopy() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.flac"
        assertEquals(uri, ShareTarget.preferredOutputUri(uri))
    }

    @Test
    fun fallsBackToCacheWhenNoPublicOutput() {
        assertNull(ShareTarget.preferredOutputUri(null))
    }

    @Test
    fun blankOutputIsTreatedAsNoPublicOutput() {
        assertNull(ShareTarget.preferredOutputUri(""))
        assertNull(ShareTarget.preferredOutputUri("   "))
    }
}
