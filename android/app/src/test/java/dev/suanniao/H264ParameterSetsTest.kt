package dev.suanniao

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class H264ParameterSetsTest {
    @Test fun nvencFirstAccessUnitFindsSpsAndPps() {
        val au = byteArrayOf(
            0,0,0,1,9,0x10,
            0,0,0,1,7,0x42,0x00,0x1f,
            0,0,0,1,8,0x01,0x02,
            0,0,0,1,5,0x65,0x44
        )
        val sets = H264ParameterSets.extract(au)
        assertNotNull(sets)
        val sps = ByteArray(sets!!.sps.remaining())
        val pps = ByteArray(sets.pps.remaining())
        sets.sps.get(sps)
        sets.pps.get(pps)
        assertArrayEquals(byteArrayOf(0,0,0,1,7,0x42,0,0x1f), sps)
        assertArrayEquals(byteArrayOf(0,0,0,1,8,1,2), pps)
    }
    @Test fun supportsThreeByteStartCodesAndRejectsPFrames() {
        val au = byteArrayOf(0,0,1,9,0x10,0,0,1,7,0x42,0,0,1,8,1)
        assertNotNull(H264ParameterSets.extract(au))
        assertNull(H264ParameterSets.extract(byteArrayOf(0,0,0,1,9,0x10,0,0,0,1,1,0x1f)))
        assertNull(H264ParameterSets.extract(byteArrayOf()))
    }
}
