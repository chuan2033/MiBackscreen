package hook.HyperBackscreen.hook.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

class TargetMembersTest {

    private class Single {
        @JvmField
        var only: String = ""

        fun unique(value: Int): Boolean = value > 0

        fun overloaded(value: String): Boolean = value.isNotEmpty()

        fun other(): String = "x"
    }

    private class Ambiguous {
        fun first(): Boolean = true

        fun second(): Boolean = false

        companion object {
            @JvmField
            var alpha: String = ""

            @JvmField
            var beta: String = ""
        }
    }

    @Test
    fun uniqueMethodMatchesBySignature() {
        val method: Method? = TargetMembers.uniqueMethod(Single::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertNotNull(method)
        assertEquals("unique", method!!.name)
    }

    @Test
    fun uniqueMethodIgnoresDifferentReturnType() {
        assertNull(TargetMembers.uniqueMethod(Single::class.java, String::class.java, Int::class.javaPrimitiveType))
    }

    @Test
    fun ambiguousSignatureYieldsNull() {
        assertNull(TargetMembers.uniqueMethod(Ambiguous::class.java, Boolean::class.javaPrimitiveType))
    }

    @Test
    fun uniqueNoArgMethodPicksTheOnlyMatch() {
        val method = TargetMembers.uniqueNoArgMethod(Single::class.java, String::class.java)
        assertNotNull(method)
        assertEquals("other", method!!.name)
    }

    @Test
    fun staticFieldByTypeRequiresUniqueness() {
        assertNull(TargetMembers.uniqueStaticFieldOfType(Ambiguous::class.java, String::class.java))
    }

    @Test
    fun instanceFieldByTypeFindsSingleMatch() {
        val field: Field? = TargetMembers.uniqueFieldOfType(Single::class.java, String::class.java)
        assertNotNull(field)
        assertEquals("only", field!!.name)
    }
}
