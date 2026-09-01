package hook.HyperBackscreen.hook;

import java.util.List;

final class SettingsEntryPlacement {
    private SettingsEntryPlacement() {
    }

    static String chooseAnchorKey(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        if (keys.contains("user_guide")) return "user_guide";
        if (keys.contains("serve_assistant")) return "serve_assistant";
        return null;
    }

    static int insertionIndexAfterAnchor(List<String> keys) {
        String anchorKey = chooseAnchorKey(keys);
        if (anchorKey == null) return -1;
        return keys.indexOf(anchorKey) + 1;
    }
}
