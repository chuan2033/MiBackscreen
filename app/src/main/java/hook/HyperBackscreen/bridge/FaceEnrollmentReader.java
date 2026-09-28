package hook.HyperBackscreen.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Reads enrollment counts only; never reads face templates or changes biometric settings. */
public final class FaceEnrollmentReader {
    public static final int UNKNOWN = -1;
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    private FaceEnrollmentReader() {}

    public static int read(int userId) {
        if (userId < 0) return UNKNOWN;
        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            // Fixed command, no caller-supplied shell text. Keep only service summary JSON;
            // authentication history, biometric IDs and templates are not returned or stored.
            process = new ProcessBuilder("su", "-c",
                    "/system/bin/dumpsys -t 1 face | /system/bin/grep '^{' ")
                    .redirectErrorStream(true).start();
            Process active = process;
            Future<String> output = reader.submit(() -> {
                StringBuilder text = new StringBuilder();
                try (InputStreamReader input = new InputStreamReader(
                        active.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (text.length() + count > MAX_OUTPUT_CHARS) {
                            throw new IllegalStateException("Face summary exceeds limit");
                        }
                        text.append(buffer, 0, count);
                    }
                }
                return text.toString();
            });
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return UNKNOWN;
            }
            return parseCount(output.get(100, TimeUnit.MILLISECONDS), userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (Exception e) {
            return UNKNOWN;
        } finally {
            if (process != null) process.destroyForcibly();
            reader.shutdownNow();
        }
    }

    /** -1 means unavailable, distinct from a confirmed empty enrollment list. */
    public static int parseCount(String output, int userId) {
        if (output == null || userId < 0 || output.length() > MAX_OUTPUT_CHARS) return UNKNOWN;
        int total = 0;
        boolean found = false;
        try (BufferedReader lines = new BufferedReader(new java.io.StringReader(output))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                JSONObject service = new JSONObject(line);
                if (!service.getString("service").startsWith("FaceProvider/")) return UNKNOWN;
                JSONArray users = service.getJSONArray("prints");
                boolean matched = false;
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.getJSONObject(i);
                    Object id = user.get("id");
                    Object count = user.get("count");
                    if (!(id instanceof Integer) || !(count instanceof Integer)
                            || (Integer) id < 0 || (Integer) count < 0) return UNKNOWN;
                    if ((Integer) id != userId) continue;
                    if (matched) return UNKNOWN;
                    matched = true;
                    total = Math.addExact(total, (Integer) count);
                }
                if (!matched) return UNKNOWN;
                found = true;
            }
            return found ? total : UNKNOWN;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
