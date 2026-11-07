package hook.HyperBackscreen.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import hook.HyperBackscreen.common.Constants;

/** 两份新名单键进了备份恢复的取值校验：按包名列表规范化，非法项丢弃。 */
public class PrefsBridgePackageListBackupTest {
    @Test
    public void newPackageListKeysAreNormalizedOnRestore() {
        for (String key : new String[]{
                Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES,
                Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES}) {
            assertEquals("com.a.music\ncom.b.player",
                    PrefsBridge.normalizeBackupString(key, " com.a.music, not a package ;com.b.player "));
            assertEquals("", PrefsBridge.normalizeBackupString(key, ""));
        }
    }
}
