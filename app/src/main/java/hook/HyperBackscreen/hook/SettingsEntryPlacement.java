package hook.HyperBackscreen.hook;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

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

    static List<Integer> insertionIndexesAfterAnchor(List<String> keys, int count) {
        int firstIndex = insertionIndexAfterAnchor(keys);
        if (firstIndex < 0 || count <= 0) return Collections.emptyList();
        ArrayList<Integer> indexes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indexes.add(firstIndex + i);
        }
        return indexes;
    }
}
