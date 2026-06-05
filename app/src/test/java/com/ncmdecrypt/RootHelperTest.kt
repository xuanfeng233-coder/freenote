package com.ncmdecrypt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootHelperTest {

    @Test
    fun detectsSuWhenAPathExists() {
        val tmp = File.createTempFile("su_fake", ".bin").apply { deleteOnExit() }
        assertTrue(RootHelper.isRootAvailable(listOf("/no/such/path", tmp.absolutePath)))
    }

    @Test
    fun reportsNoRootWhenNoPathExists() {
        assertFalse(RootHelper.isRootAvailable(listOf("/no/such/path", "/also/missing")))
    }
}
