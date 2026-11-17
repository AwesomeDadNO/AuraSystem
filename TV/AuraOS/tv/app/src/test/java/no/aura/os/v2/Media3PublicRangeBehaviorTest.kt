package no.aura.os.v2

import no.aura.os.v2.livetv.PublicMediaRange
import org.junit.Assert.*
import org.junit.Test

class Media3PublicRangeBehaviorTest {
    @Test fun partialResponsesMustMatchExactRequestedRange() {
        assertEquals(100L, PublicMediaRange.validate(206,"bytes 50-149/1000",100,50,100,1000))
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.validate(206,"bytes 0-99/1000",100,50,100,1000) }
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.validate(200,null,1000,50,100,1000) }
    }
    @Test fun unboundedResponseAndRedirectsCannotBypassResourceLimits() {
        assertEquals(-1L,PublicMediaRange.validate(200,null,-1,0,-1,1000))
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.validate(200,null,1001,0,-1,1000) }
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.validate(302,null,0,0,-1,1000) }
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.validate(206,"bytes 0-999999999999999999999/*",-1,0,-1,1000) }
    }
    @Test fun rangesCannotOverflowOrExceedLimit() {
        assertEquals("bytes=50-149", PublicMediaRange.header(50,100,1000))
        assertEquals("bytes=50-", PublicMediaRange.header(50,-1,1000))
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.header(Long.MAX_VALUE,100,1000) }
        assertThrows(java.io.IOException::class.java) { PublicMediaRange.header(0,1001,1000) }
    }
}
