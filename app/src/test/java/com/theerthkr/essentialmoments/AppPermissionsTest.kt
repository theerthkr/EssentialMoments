package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.permissions.AppPermissions
import com.theerthkr.essentialmoments.permissions.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionsTest {

    @Test
    fun android11To12_usesReadExternalStorageOnly() {
        assertEquals(
            MediaAccess.FULL,
            AppPermissions.classifyMediaAccess(
                sdkInt = 30,
                hasReadExternalStorage = true,
                hasReadMediaImages = false,
                hasVisualUserSelected = false
            )
        )
        assertEquals(
            MediaAccess.NONE,
            AppPermissions.classifyMediaAccess(
                sdkInt = 32,
                hasReadExternalStorage = false,
                hasReadMediaImages = true,
                hasVisualUserSelected = true
            )
        )
    }

    @Test
    fun android13_hasFullOrDeniedOnly() {
        assertEquals(
            MediaAccess.FULL,
            AppPermissions.classifyMediaAccess(
                sdkInt = 33,
                hasReadExternalStorage = false,
                hasReadMediaImages = true,
                hasVisualUserSelected = false
            )
        )
        assertEquals(
            MediaAccess.NONE,
            AppPermissions.classifyMediaAccess(
                sdkInt = 33,
                hasReadExternalStorage = false,
                hasReadMediaImages = false,
                hasVisualUserSelected = true
            )
        )
    }

    @Test
    fun android14_distinguishesFullPartialAndDenied() {
        assertEquals(
            MediaAccess.FULL,
            AppPermissions.classifyMediaAccess(
                sdkInt = 34,
                hasReadExternalStorage = false,
                hasReadMediaImages = true,
                hasVisualUserSelected = false
            )
        )
        assertEquals(
            MediaAccess.PARTIAL,
            AppPermissions.classifyMediaAccess(
                sdkInt = 34,
                hasReadExternalStorage = false,
                hasReadMediaImages = false,
                hasVisualUserSelected = true
            )
        )
        assertEquals(
            MediaAccess.NONE,
            AppPermissions.classifyMediaAccess(
                sdkInt = 34,
                hasReadExternalStorage = false,
                hasReadMediaImages = false,
                hasVisualUserSelected = false
            )
        )
    }
}
