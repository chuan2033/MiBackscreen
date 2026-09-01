package hook.HyperBackscreen.cleanup;

import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RemovedRecordingFeaturesTest {
    private static final List<String> REMOVED_MARKERS = Arrays.asList(
            "RearLiveCaptureRecorder",
            "ScreenRecorderRearMirrorHook",
            "ScreenRecorderMirrorPolicy",
            "com.miui.screenshot",
            "com.miui.screenrecorder",
            "rear_live_capture",
            "mirror_screen_recorder_to_rear",
            "config_mirror_screen_recorder",
            "config_rear_live_capture",
            "SCREENSHOT_PACKAGE",
            "SCREEN_RECORDER_PACKAGE"
    );

    @Test
    public void mainSourcesDoNotKeepRearCaptureOrRecorderHooks() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path main = root.resolve("src/main");
        StringBuilder matches = new StringBuilder();
        try (var paths = Files.walk(main)) {
            paths.filter(Files::isRegularFile)
                    .filter(RemovedRecordingFeaturesTest::isTextSource)
                    .forEach(path -> collectMatches(path, matches));
        }
        assertFalse(matches.toString(), matches.length() > 0);
    }

    private static boolean isTextSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java")
                || name.endsWith(".kt")
                || name.endsWith(".xml")
                || name.endsWith(".list")
                || name.endsWith(".properties");
    }

    private static void collectMatches(Path path, StringBuilder matches) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (String marker : REMOVED_MARKERS) {
                if (text.contains(marker)) {
                    matches.append(path).append(" contains ").append(marker).append('\n');
                }
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to scan " + path, e);
        }
    }
}
