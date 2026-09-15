package hook.HyperBackscreen.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PickupCodes {
    public static final String ACTION_VIEW = Constants.MODULE_PACKAGE + ".VIEW_PICKUP_CODES";
    public static final String ACTION_CONFIRMED = Constants.MODULE_PACKAGE + ".PICKUP_CONFIRMED";
    public static final String ACTION_PAGE_OPENED = Constants.MODULE_PACKAGE + ".PICKUP_PAGE_OPENED";
    public static final String EXTRA_CODES = "pickup_codes";
    public static final String EXTRA_STATION = "pickup_station";
    public static final String EXTRA_SESSION = "pickup_session";
    public static final String EXTRA_TOKEN = "pickup_token";
    public static final String EXTRA_IDENTITY = "pickup_identity";
    public static final int MAX_TITLE_LENGTH = 4096;
    public static final int MAX_STATION_LENGTH = 512;
    public static final int MAX_CODES = 64;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SEPARATOR = Pattern.compile("[,\uFF0C;\uFF1B\r\n\u3001]+");

    private PickupCodes() {}

    public static List<String> parse(String title) {
        if (title == null || title.length() > MAX_TITLE_LENGTH) return Collections.emptyList();
        String[] parts = SEPARATOR.split(title, -1);
        if (parts.length > MAX_CODES) return Collections.emptyList();
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String part : parts) {
            String code = part.trim();
            if (code.isEmpty() || code.length() < 2 || code.length() > 32
                    || !CODE.matcher(code).matches() || !DIGIT.matcher(code).find()) {
                return Collections.emptyList();
            }
            codes.add(code);
        }
        return Collections.unmodifiableList(new ArrayList<>(codes));
    }

    public static boolean shouldAddEntry(String scene, String title) {
        return "delivery".equals(scene) && parse(title).size() > 1;
    }

    public static String station(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), MAX_STATION_LENGTH)).trim();
    }

    private static final String SELECTION_SEPARATOR = "|";
    private static final String CODE_SEPARATOR = ",";
    private static final String SELECTION_RECORD_SEPARATOR = "\n";
    private static final String NO_CODES_MARKER = "!";
    private static final int MAX_SELECTION_LENGTH = 8192;

    /** 一次识别的稳定标识（与 Hook 端用于 PendingIntent 的 identity 一致）。 */
    public static String identity(List<String> codes, String station) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((String.join(CODE_SEPARATOR, codes) + "\n" + station)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /** 同一驿站的多次识别共用一个选择标识，新增取件码不会让旧开关失效。 */
    public static String stationIdentity(String station) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("station\n" + station).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /** 有站点时按站点合并；站点缺失时按本次完整码组隔离。 */
    public static String displayIdentity(List<String> codes, String station) {
        return station == null || station.isEmpty() ? identity(codes, "") : stationIdentity(station);
    }

    /** 岛显示选择编码：identity|code,code 。 */
    public static String encodeIslandSelection(String identity, List<String> visible) {
        if (identity == null || identity.isEmpty()) return "";
        return identity + SELECTION_SEPARATOR + String.join(CODE_SEPARATOR, visible);
    }

    public static String encodeHiddenIslandSelection(String identity) {
        if (identity == null || identity.isEmpty()) return "";
        return identity + SELECTION_SEPARATOR + NO_CODES_MARKER;
    }

    /** 从多驿站选择记录中取出一个 identity 的记录；也兼容旧版单条记录。 */
    public static String selectionForIdentity(String stored, String identity) {
        if (stored == null || stored.isEmpty() || identity == null || identity.isEmpty()) return "";
        if (stored.length() > MAX_SELECTION_LENGTH) return "";
        String prefix = identity + SELECTION_SEPARATOR;
        for (String record : stored.split(SELECTION_RECORD_SEPARATOR, -1)) {
            if (record.startsWith(prefix) && isValidSelectionRecord(record)) return record;
        }
        return "";
    }

    /** 更新一个驿站的选择记录，保留其它驿站的记录。 */
    public static String upsertIslandSelection(String stored, String identity,
                                               List<String> visible, boolean showAll) {
        if (identity == null || identity.isEmpty()) return stored == null ? "" : stored;
        String prefix = identity + SELECTION_SEPARATOR;
        List<String> records = new ArrayList<>();
        if (stored != null && !stored.isEmpty()) {
            for (String record : stored.split(SELECTION_RECORD_SEPARATOR, -1)) {
                if (!record.isEmpty() && !record.startsWith(prefix) && isValidSelectionRecord(record)) {
                    records.add(record);
                }
            }
        }
        if (!showAll) {
            records.add(visible.isEmpty()
                    ? encodeHiddenIslandSelection(identity)
                    : encodeIslandSelection(identity, visible));
        }
        String result = String.join(SELECTION_RECORD_SEPARATOR, records);
        return result.length() > MAX_SELECTION_LENGTH ? stored == null ? "" : stored : result;
    }

    /**
     * 依据已保存的选择过滤本次识别到的取件码。
     * 无有效选择（没选过 / 与本次识别不匹配）时返回原列表，即默认全部显示。
     */
    public static List<String> applyIslandSelection(List<String> codes, String identity, String stored) {
        stored = selectionForIdentity(stored, identity);
        if (stored.isEmpty()) return codes;
        int separator = stored.indexOf(SELECTION_SEPARATOR);
        if (separator <= 0 || !stored.substring(0, separator).equals(identity)) return codes;
        String csv = stored.substring(separator + 1);
        if (NO_CODES_MARKER.equals(csv)) return Collections.emptyList();
        if (csv.isEmpty()) return codes;
        String padded = CODE_SEPARATOR + csv + CODE_SEPARATOR;
        List<String> visible = new ArrayList<>();
        for (String code : codes) {
            if (padded.contains(CODE_SEPARATOR + code + CODE_SEPARATOR)) visible.add(code);
        }
        return visible;
    }

    private static boolean isValidSelectionRecord(String record) {
        int separator = record.indexOf(SELECTION_SEPARATOR);
        if (separator <= 0 || separator != record.lastIndexOf(SELECTION_SEPARATOR)) return false;
        String identity = record.substring(0, separator);
        if (!identity.matches("[0-9a-f]{64}")) return false;
        String value = record.substring(separator + 1);
        if (NO_CODES_MARKER.equals(value)) return true;
        if (value.isEmpty()) return false;
        List<String> parsed = parse(value);
        return !parsed.isEmpty() && String.join(CODE_SEPARATOR, parsed).equals(value);
    }

    /** 多张卡片合并：保留 existing 顺序，追加 incoming 中尚未出现的码（去重保序）。 */
    public static List<String> mergeDistinct(List<String> existing, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
        merged.addAll(incoming);
        return Collections.unmodifiableList(new ArrayList<>(merged));
    }

    public static final int MAX_ISLAND_CODES = 4;
    public static final int ISLAND_CODES_PER_ROW = 2;
    public static final int ISLAND_MAX_ROWS = MAX_ISLAND_CODES / ISLAND_CODES_PER_ROW;
    private static final String ISLAND_CODE_GAP = " ";

    public static String formatCollapsedText(List<String> codes) {
        if (codes.isEmpty()) return "取件码";
        return String.join(",", codes.subList(0, Math.min(codes.size(), MAX_ISLAND_CODES)));
    }

    /**
     * 记忆岛卡片显示文本：每行两个取件码、最多 4 个（2 行），多出的列表在取件码页里看。
     * 只是显示排版，不影响识别结果本身。
     */
    public static String formatIslandText(List<String> codes) {
        if (codes.isEmpty()) return "取件码";
        int limit = Math.min(codes.size(), MAX_ISLAND_CODES);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                if (i % ISLAND_CODES_PER_ROW == 0) builder.append('\n');
                else builder.append(ISLAND_CODE_GAP);
            }
            builder.append(codes.get(i));
        }
        return builder.toString();
    }
}
