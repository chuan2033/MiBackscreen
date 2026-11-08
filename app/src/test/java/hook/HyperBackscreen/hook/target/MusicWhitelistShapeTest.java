package hook.HyperBackscreen.hook.target;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;

import org.junit.Test;

public class MusicWhitelistShapeTest {
    /** 仿宿主 m2.a：c(String)boolean、b()HashSet，外加 a(String)String、d(String,String)boolean 干扰项。 */
    @SuppressWarnings("unused")
    static final class HostLike {
        static final HashMap<String, String> TYPES = new HashMap<>();

        static String a(String pkg) { return TYPES.get(pkg); }
        static HashSet<String> b() { return new HashSet<>(); }
        static boolean c(String pkg) { return "unified.music".equals(pkg); }
        static boolean d(String pkg, String type) { return false; }
    }

    /** 调用方那种类：有同样的字符串，但方法不是静态的。 */
    @SuppressWarnings("unused")
    static final class InstanceMethods {
        boolean c(String pkg) { return false; }
        HashSet<String> b() { return new HashSet<>(); }
    }

    /** 两个 static (String)boolean，不唯一。 */
    @SuppressWarnings("unused")
    static final class AmbiguousCheck {
        static boolean c(String pkg) { return false; }
        static boolean e(String pkg) { return true; }
        static HashSet<String> b() { return new HashSet<>(); }
    }

    /** 返回类型是 LinkedHashSet 而不是 HashSet：合并时塞回 HashSet 会类型不符，必须拒绝。 */
    @SuppressWarnings("unused")
    static final class WrongSetType {
        static boolean c(String pkg) { return false; }
        static LinkedHashSet<String> b() { return new LinkedHashSet<>(); }
    }

    @SuppressWarnings("unused")
    static final class MissingSet {
        static boolean c(String pkg) { return false; }
    }

    @Test
    public void hostShapedClassMatchesAndResolvesBothMethods() {
        assertTrue(MusicWhitelistShape.matches(HostLike.class));
        assertEquals("c", MusicWhitelistShape.memberCheckMethod(HostLike.class).getName());
        assertEquals("b", MusicWhitelistShape.whitelistMethod(HostLike.class).getName());
    }

    @Test
    public void instanceMethodsDoNotMatch() {
        assertFalse(MusicWhitelistShape.matches(InstanceMethods.class));
    }

    @Test
    public void ambiguousMemberCheckIsRejected() {
        assertNull(MusicWhitelistShape.memberCheckMethod(AmbiguousCheck.class));
        assertFalse(MusicWhitelistShape.matches(AmbiguousCheck.class));
    }

    @Test
    public void setGetterMustReturnExactlyHashSet() {
        assertNull(MusicWhitelistShape.whitelistMethod(WrongSetType.class));
        assertFalse(MusicWhitelistShape.matches(WrongSetType.class));
    }

    @Test
    public void missingMethodOrNullClassDoesNotMatch() {
        assertFalse(MusicWhitelistShape.matches(MissingSet.class));
        assertFalse(MusicWhitelistShape.matches(null));
    }
}
