package net.typeblog.socks.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the hev-socks5-tunnel config generator.
 * Pure java.io string building — no Android framework calls — so these run
 * with testDebugUnitTest on CI, no emulator needed.
 */
class HevConfTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun basicConfHasTunnelSocksAndMisc() {
        val path = Utility.makeHevConf(tmp.root.absolutePath, "1.2.3.4", 1080, null, null, false)
        val f = File(path)
        assertTrue("conf file must exist at $path", f.exists())
        val c = f.readText()
        assertTrue(c.contains("mtu: 1500"))
        assertTrue(c.contains("ipv4: 10.10.10.2"))
        assertTrue(c.contains("address: '1.2.3.4'"))
        assertTrue(c.contains("port: 1080"))
        assertFalse("no udp line: proxies are TCP-only", c.contains("udp:"))
        assertTrue(c.contains("log-level: info"))
        assertTrue(c.contains("log-file: '"))
        assertTrue(c.contains("udp-read-write-timeout: 60000"))
        assertFalse("no auth section without username", c.contains("username:"))
        assertFalse("no ipv6 line when disabled", c.contains("ipv6:"))
    }

    @Test
    fun authAndIpv6AreEmitted() {
        val path = Utility.makeHevConf(tmp.root.absolutePath, "example.com", 1081, "bob", "s3cret", true)
        val c = File(path).readText()
        assertTrue(c.contains("username: 'bob'"))
        assertTrue(c.contains("password: 's3cret'"))
        assertTrue(c.contains("ipv6: 'fdfe:dcba:9876::2'"))
    }

    @Test
    fun singleQuotesAreYamlEscaped() {
        val path = Utility.makeHevConf(tmp.root.absolutePath, "1.2.3.4", 1080, "o'brien", "p'a'ss", false)
        val c = File(path).readText()
        assertTrue(c.contains("username: 'o''brien'"))
        assertTrue(c.contains("password: 'p''a''ss'"))
    }

    @Test
    fun noUdpLineEverEmitted() {
        val path = Utility.makeHevConf(tmp.root.absolutePath, "1.2.3.4", 1080, null, null, false)
        val c = File(path).readText()
        assertFalse("no udp line: proxies are TCP-only", c.contains("udp:"))
        assertTrue(c.contains("address: '1.2.3.4'"))
    }
}
