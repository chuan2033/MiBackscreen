package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

public class PackageListCodecTest {
    @Test
    public void parsePackageNamesTrimsDeduplicatesAndFiltersInvalidTokens() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "com.tencent.tmgp.sgame",
                        "com.miHoYo.Nap",
                        "com.kurogame.mingchao")),
                PackageListCodec.parse(
                        " com.tencent.tmgp.sgame\n"
                                + "com.miHoYo.Nap, com.tencent.tmgp.sgame；bad/pkg\n"
                                + "com.kurogame.mingchao，"));
    }

    @Test
    public void encodePackageNamesPreservesStableLineOrder() {
        assertEquals(
                "com.tencent.tmgp.sgame\ncom.miHoYo.Nap",
                PackageListCodec.encode(new LinkedHashSet<>(Arrays.asList(
                        "com.tencent.tmgp.sgame",
                        "com.miHoYo.Nap"))));
    }
}
