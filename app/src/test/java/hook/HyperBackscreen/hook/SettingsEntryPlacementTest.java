package hook.HyperBackscreen.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SettingsEntryPlacementTest {
    @Test
    public void choosesUserGuideAsPreferredAnchor() {
        assertEquals(
                "user_guide",
                SettingsEntryPlacement.chooseAnchorKey(Arrays.asList(
                        "subscreen_personality",
                        "serve_assistant",
                        "subscreen_aod",
                        "user_guide",
                        "phone_case")));
    }

    @Test
    public void fallsBackToServiceAssistantWhenUserGuideIsMissing() {
        assertEquals(
                "serve_assistant",
                SettingsEntryPlacement.chooseAnchorKey(Arrays.asList(
                        "subscreen_personality",
                        "serve_assistant",
                        "subscreen_aod")));
    }

    @Test
    public void returnsNullWhenNoKnownAnchorExists() {
        assertNull(SettingsEntryPlacement.chooseAnchorKey(Collections.singletonList("phone_case")));
    }

    @Test
    public void insertsAfterUserGuideOnThemeManagerBackscreenPage() {
        assertEquals(
                7,
                SettingsEntryPlacement.insertionIndexAfterAnchor(Arrays.asList(
                        "subscreen_person",
                        "person_operation",
                        "serve_assistant",
                        "notification",
                        "aod",
                        "screenshot",
                        "user_guide",
                        "phone_case",
                        "brightness")));
    }
}
