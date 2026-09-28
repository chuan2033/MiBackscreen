package hook.HyperBackscreen.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Host builds create batches; repainting an existing notification never creates a new batch. */
public final class PickupSessions {
    private final Map<String, String> sessions = new LinkedHashMap<>();
    private long lastBuild = -1;
    private String current = "";

    public synchronized String forCard(String identity, long now, boolean hostBuild) {
        if (!hostBuild && sessions.containsKey(identity)) return sessions.get(identity);
        if (lastBuild < 0 || now - lastBuild > 2000) current = Long.toHexString(now);
        if (hostBuild || lastBuild < 0) lastBuild = now;
        sessions.put(identity, current);
        while (sessions.size() > 64) sessions.remove(sessions.keySet().iterator().next());
        return current;
    }
}
