package app.twoeno.extension.interpals

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class InterPalsExtensionTest {
    @Test
    fun disablePlacements() {
        val result = DisableAdPlacementsPatch.disablePlacements(
            """{"a":{"enabled":true},"b":{"enabled" : false},"c":"{\"enabled\":true}"}"""
        )
        assertEquals("""{"a":{"enabled":false},"b":{"enabled" : false},"c":"{\"enabled\":false}"}""", result[0])
        assertEquals(2, result[1])
    }

    private fun int32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private val magic = byteArrayOf(0xC6.toByte(), 0x1F, 0xBC.toByte(), 0x03, 0xC1.toByte(), 0x03, 0x19, 0x1F)
    private val swiperDefault = byteArrayOf(0x11, 0x72, 0x03, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00)

    private fun bundle(version: Int = 98, swiperDefaults: Int = 1): ByteArray {
        var body = magic + int32(version) + "album_swiper_ad".toByteArray()
        repeat(swiperDefaults) { body += byteArrayOf(0x00) + swiperDefault }
        body += byteArrayOf(0x8C.toByte(), 0x05) + int32(6 * 60 * 60 * 1000)
        // Not loaded by LoadConstInt.
        body += byteArrayOf(0x00, 0x05) + int32(6 * 60 * 60 * 1000)
        return body + MessageDigest.getInstance("SHA-1").digest(body)
    }

    @Test
    fun patchHermesBundle() {
        val original = bundle()
        val result = HermesBundlePatcher.patch(original)
        val patched = result.bundle!!
        assertEquals(original.size, patched.size)

        val swiperIndex = magic.size + 4 + "album_swiper_ad".length + 1
        assertEquals(0x21.toByte(), patched[swiperIndex])
        val ttlIndex = swiperIndex + swiperDefault.size + 2
        assertArrayEquals(int32(60 * 1000), patched.copyOfRange(ttlIndex, ttlIndex + 4))
        // The other occurrence of the cache ttl stays.
        assertArrayEquals(int32(6 * 60 * 60 * 1000), patched.copyOfRange(ttlIndex + 6, ttlIndex + 10))

        val hashOffset = patched.size - 20
        assertArrayEquals(
            MessageDigest.getInstance("SHA-1").digest(patched.copyOfRange(0, hashOffset)),
            patched.copyOfRange(hashOffset, patched.size),
        )
        assertEquals(2, result.description.split(", ").size)
    }

    @Test
    fun skipUnknownBundles() {
        assertNull(HermesBundlePatcher.patch("console.log(1)".toByteArray() + ByteArray(40)).bundle)
        assertNull(HermesBundlePatcher.patch(bundle(version = 99)).bundle)
        // An ambiguous swiper default is not changed, the cache ttl still is.
        val result = HermesBundlePatcher.patch(bundle(swiperDefaults = 2))
        assertEquals(1, result.description.split(", ").size)
    }
}
