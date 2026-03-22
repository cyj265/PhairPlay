package com.phairplay.util

import android.content.Context
import android.content.ContentResolver
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NetworkUtilsTest — Unit tests for NetworkUtils.
 *
 * WHY: NetworkUtils reads critical information (device name, MAC address) that
 * is used in mDNS TXT records. If these values are wrong or malformed, macOS
 * may reject the device or fail to connect. We need to verify:
 * - The device name is correctly read and sanitized
 * - Invalid characters in device names are removed (security + compatibility)
 * - Fallback values are used when system settings are unavailable
 */
class NetworkUtilsTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    /**
     * Test: getDeviceName returns the device name from Settings.Global.
     *
     * WHY: The primary source for device name is Settings.Global.DEVICE_NAME.
     * If this works correctly, macOS users will see the device's configured name.
     */
    @Test
    fun `getDeviceName returns device name from system settings`() {
        // Mock the system settings to return a specific device name
        every {
            android.provider.Settings.Global.getString(mockContentResolver, "device_name")
        } returns "My TV"

        val name = NetworkUtils.getDeviceName(mockContext)

        assertEquals("My TV", name)
    }

    /**
     * Test: getDeviceName uses fallback when settings return null.
     *
     * WHY: On some devices or in some configurations, Settings.Global.DEVICE_NAME
     * may be null. We must return a non-null, non-empty default name.
     */
    @Test
    fun `getDeviceName returns default when settings return null`() {
        every {
            android.provider.Settings.Global.getString(mockContentResolver, "device_name")
        } returns null
        every {
            android.provider.Settings.Secure.getString(mockContentResolver, "bluetooth_name")
        } returns null

        val name = NetworkUtils.getDeviceName(mockContext)

        assertNotNull("Device name should never be null", name)
        assertFalse("Device name should not be empty", name.isEmpty())
        assertEquals("PhairPlay", name)
    }

    /**
     * Test: getDeviceName sanitizes special characters.
     *
     * WHY: SECURITY — mDNS service names have character restrictions.
     * A device name with special characters (like "<script>") could cause
     * issues in mDNS TXT records. We strip anything outside [A-Za-z0-9 _-].
     */
    @Test
    fun `getDeviceName strips special characters from device name`() {
        every {
            android.provider.Settings.Global.getString(mockContentResolver, "device_name")
        } returns "My <TV> & Device!"

        val name = NetworkUtils.getDeviceName(mockContext)

        // Only alphanumeric, space, underscore, hyphen should remain
        assertFalse("Should not contain <", "<" in name)
        assertFalse("Should not contain >", ">" in name)
        assertFalse("Should not contain &", "&" in name)
        assertFalse("Should not contain !", "!" in name)
    }

    /**
     * Test: getDeviceName returns fallback for a name that is all special characters.
     *
     * WHY: After sanitization, if nothing is left (e.g., the name was "!!!"),
     * we must still return a valid non-empty fallback name.
     */
    @Test
    fun `getDeviceName returns fallback when name is all special characters`() {
        every {
            android.provider.Settings.Global.getString(mockContentResolver, "device_name")
        } returns "!!!"
        every {
            android.provider.Settings.Secure.getString(mockContentResolver, "bluetooth_name")
        } returns null

        val name = NetworkUtils.getDeviceName(mockContext)

        assertFalse("Name should not be empty after sanitization", name.isEmpty())
    }

    /**
     * Test: getMacAddress returns a string in valid MAC address format.
     *
     * WHY: The MAC address is used as `deviceid` in AirPlay mDNS TXT records.
     * The format must be "xx:xx:xx:xx:xx:xx" (6 hex groups, colon-separated).
     * An incorrectly formatted MAC address would cause macOS to reject or
     * misidentify the device.
     *
     * NOTE: Since we can't enumerate real network interfaces in a unit test
     * environment, this test verifies the fallback MAC address format is correct.
     */
    @Test
    fun `getMacAddress returns a valid MAC address format`() {
        val mac = NetworkUtils.getMacAddress()

        assertNotNull("MAC address should not be null", mac)
        // MAC address format: 6 groups of 2 hex digits separated by colons
        assertTrue(
            "MAC address should match format xx:xx:xx:xx:xx:xx but was: $mac",
            mac.matches(Regex("[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}"))
        )
    }
}
