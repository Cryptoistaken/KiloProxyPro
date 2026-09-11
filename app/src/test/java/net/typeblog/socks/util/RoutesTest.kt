package net.typeblog.socks.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the route carve-out used by the hev engine path.
 * Pure CIDR math — no Android framework calls — so these run with
 * testDebugUnitTest on CI, no emulator needed.
 */
class RoutesTest {

    /** Independent coverage check, written straight from the CIDR spec. */
    private fun covers(cidr: String, ip: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val base = toLong(parts[0]) ?: return false
        val len = parts[1].toIntOrNull() ?: return false
        val target = toLong(ip) ?: return false
        if (len == 0) return true
        val mask = (-1L shl (32 - len)) and 0xFFFFFFFFL
        return (base and mask) == (target and mask)
    }

    private fun toLong(ip: String): Long? {
        val p = ip.split(".")
        if (p.size != 4) return null
        var v = 0L
        for (oct in p) {
            val o = oct.toIntOrNull() ?: return null
            if (o < 0 || o > 255) return null
            v = (v shl 8) or o.toLong()
        }
        return v
    }

    private fun coversAny(routes: List<String>, ip: String) = routes.any { covers(it, ip) }

    @Test
    fun allMinusDnsExcludesOnlyDns() {
        val out = Routes.excludeIpv4(listOf("0.0.0.0/0"), "8.8.8.8")
        assertEquals("0.0.0.0/0 minus one host is 32 prefixes", 32, out.size)
        assertFalse("carved list must not cover 8.8.8.8", coversAny(out, "8.8.8.8"))
        for (ip in listOf("0.0.0.0", "1.2.3.4", "7.255.255.255", "8.8.8.7",
            "8.8.8.9", "8.8.8.255", "8.8.4.4", "9.0.0.0", "192.168.1.1",
            "255.255.255.255")) {
            assertTrue("carved list must still cover $ip", coversAny(out, ip))
        }
    }

    @Test
    fun carveEmitsExpectedBoundaryPrefixes() {
        val out = Routes.excludeIpv4(listOf("0.0.0.0/0"), "8.8.8.8")
        assertTrue(out.contains("128.0.0.0/1"))
        assertTrue(out.contains("8.8.8.10/31"))
        assertTrue(out.contains("8.8.8.9/32"))
        assertFalse(out.contains("8.8.8.8/32"))
    }

    @Test
    fun untouchedListPassesThrough() {
        assertEquals(listOf("10.0.0.0/8"),
            Routes.excludeIpv4(listOf("10.0.0.0/8"), "8.8.8.8"))
    }

    @Test
    fun exactHostIsDropped() {
        assertTrue(Routes.excludeIpv4(listOf("8.8.8.8/32"), "8.8.8.8").isEmpty())
        assertEquals(listOf("8.8.8.9/32"),
            Routes.excludeIpv4(listOf("8.8.8.9/32"), "8.8.8.8"))
    }

    @Test
    fun badInputPassesThrough() {
        assertEquals(listOf("nope"), Routes.excludeIpv4(listOf("nope"), "8.8.8.8"))
        val routes = listOf("0.0.0.0/0")
        assertEquals(routes, Routes.excludeIpv4(routes, "not-an-ip"))
    }
}
