package hook.HyperBackscreen.hook.subscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

public class MusicWidgetWhitelistTest {
    @Test
    public void parsesAndCachesByRawString() {
        MusicWidgetWhitelist whitelist = new MusicWidgetWhitelist();
        Set<String> first = whitelist.packages("com.a.music\ncom.b.player");
        assertEquals(new HashSet<>(Arrays.asList("com.a.music", "com.b.player")), first);
        assertSame(first, whitelist.packages("com.a.music\ncom.b.player"));
        assertEquals(Collections.singleton("com.c.app"), whitelist.packages("com.c.app"));
        assertTrue(whitelist.packages(null).isEmpty());
    }

    @Test
    public void onlyListedPackagesAreForcedTrue() {
        Set<String> extra = Collections.singleton("com.a.music");
        assertTrue(MusicWidgetWhitelist.isExtraMusicApp(extra, "com.a.music"));
        assertFalse(MusicWidgetWhitelist.isExtraMusicApp(extra, "com.other"));
        assertFalse(MusicWidgetWhitelist.isExtraMusicApp(extra, null));
        assertFalse(MusicWidgetWhitelist.isExtraMusicApp(Collections.emptySet(), "com.a.music"));
    }

    @Test
    public void emptyListLeavesOriginalUntouched() {
        HashSet<String> original = new HashSet<>(Collections.singleton("com.netease.cloudmusic"));
        assertSame(original, MusicWidgetWhitelist.merge(original, Collections.emptySet()));
        assertSame(null, MusicWidgetWhitelist.merge(null, Collections.emptySet()));
    }

    @Test
    public void mergeReturnsNewHashSetWithBothLists() {
        HashSet<String> original = new HashSet<>(Collections.singleton("com.netease.cloudmusic"));
        Object merged = MusicWidgetWhitelist.merge(original, Collections.singleton("com.a.music"));
        assertTrue(merged instanceof HashSet);
        assertNotSame(original, merged);
        assertEquals(new HashSet<>(Arrays.asList("com.netease.cloudmusic", "com.a.music")), merged);
        assertEquals(1, original.size());
        assertEquals(Collections.singleton("com.a.music"),
                MusicWidgetWhitelist.merge(null, Collections.singleton("com.a.music")));
    }

    @Test
    public void unexpectedReturnTypeIsPassedThrough() {
        TreeSet<String> original = new TreeSet<>();
        assertSame(original, MusicWidgetWhitelist.merge(original, Collections.singleton("com.a.music")));
    }
}
