package hook.HyperBackscreen.ui.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryRingGeometryTest {
    @Test fun calibratedDevicesIncludePopsicleAndPandora() {
        assertTrue(BatteryRingGeometry.isCalibratedDevice("popsicle"))
        assertTrue(BatteryRingGeometry.isCalibratedDevice("pandora"))
        assertNull(BatteryRingGeometry.forDevice("unknown"))
    }

    @Test fun pandoraUsesQ200ReferenceCanvas() {
        val geometry = BatteryRingGeometry.forDevice("pandora")
        assertNotNull(geometry)
        assertEquals(904f, geometry!!.referenceWidth, 0.001f)
        assertEquals(572f, geometry.referenceHeight, 0.001f)
        assertTrue(geometry.matchesSize(904, 572))
    }

    @Test fun popsicleKeepsExistingReferenceCanvas() {
        val geometry = BatteryRingGeometry.forDevice("popsicle")
        assertNotNull(geometry)
        assertEquals(976f, geometry!!.referenceWidth, 0.001f)
        assertEquals(596f, geometry.referenceHeight, 0.001f)
        assertTrue(geometry.matchesSize(976, 596))
    }
}
