package com.personalai.primary.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDeviceRoleTest {

    @Test
    fun `parses valid role names case insensitively and defaults unknown or blank to UNSELECTED`() {
        assertEquals(AppDeviceRole.PRIMARY, AppDeviceRole.parseOrDefault("PRIMARY"))
        assertEquals(AppDeviceRole.PRIMARY, AppDeviceRole.parseOrDefault(" primary "))
        assertEquals(AppDeviceRole.SECONDARY, AppDeviceRole.parseOrDefault("SECONDARY"))
        assertEquals(AppDeviceRole.SECONDARY, AppDeviceRole.parseOrDefault("secondary"))
        assertEquals(AppDeviceRole.UNSELECTED, AppDeviceRole.parseOrDefault(null))
        assertEquals(AppDeviceRole.UNSELECTED, AppDeviceRole.parseOrDefault(""))
        assertEquals(AppDeviceRole.UNSELECTED, AppDeviceRole.parseOrDefault("   "))
        assertEquals(AppDeviceRole.UNSELECTED, AppDeviceRole.parseOrDefault("UNKNOWN_MODE"))
    }
}
