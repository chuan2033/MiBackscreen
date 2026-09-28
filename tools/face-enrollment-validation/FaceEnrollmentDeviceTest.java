import hook.HyperBackscreen.bridge.FaceEnrollmentReader;

/** Run with Android app_process so tests use the same org.json implementation as the phone. */
public final class FaceEnrollmentDeviceTest {
    private static int passed;
    private static String summary(String entries) {
        return "{\"service\":\"FaceProvider/default\",\"prints\":[" + entries + "]}";
    }
    private static void check(String label, int expected, String input, int user) {
        int actual = FaceEnrollmentReader.parseCount(input, user);
        if (actual != expected) throw new AssertionError(label + ": " + actual + " != " + expected);
        passed++;
    }
    public static void main(String[] args) {
        String enrolled = summary("{\"id\":0,\"count\":2},{\"id\":999,\"count\":0}");
        check("existing faces", 2, enrolled, 0);
        check("another user must not inherit faces", 0, enrolled, 999);
        check("missing user", -1, enrolled, 10);
        check("no enrollment", 0, summary("{\"id\":0,\"count\":0}"), 0);
        check("missing output", -1, "", 0);
        check("permission denied", -1, "Permission Denial", 0);
        check("truncated response", -1, enrolled.substring(0, enrolled.length() - 4), 0);
        check("negative count", -1, summary("{\"id\":0,\"count\":-1}"), 0);
        check("string count", -1, summary("{\"id\":0,\"count\":\"2\"}"), 0);
        check("missing count", -1, summary("{\"id\":0}"), 0);
        check("duplicate user", -1, summary("{\"id\":0,\"count\":2},{\"id\":0,\"count\":2}"), 0);
        check("fingerprints are not faces", -1, enrolled.replace("FaceProvider", "FingerprintProvider"), 0);
        check("multiple sensors", 3, enrolled + "\n" + summary("{\"id\":0,\"count\":1}"), 0);
        check("overflow", -1, summary("{\"id\":0,\"count\":2147483647}") + "\n" + enrolled, 0);
        check("oversized output", -1, " ".repeat(65537), 0);
        // Sequential fresh reads: a prior positive value must not survive deletion or an error.
        check("removed after enrolled", 0, summary("{\"id\":0,\"count\":0}"), 0);
        check("error after enrolled", -1, "su: denied", 0);
        System.out.println("PASS " + passed + " face-enrollment parser cases");
        if (args.length > 0 && "live".equals(args[0])) {
            System.out.println("LIVE face count=" + FaceEnrollmentReader.read(0));
        }
    }
}
