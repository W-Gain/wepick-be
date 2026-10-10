package db.migration;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** V5에서 DB I/O와 분리해 검증할 수 있는 고정 정규화·prefix 판별 규칙입니다. */
public final class TopicDraftV5Logic {
    private static final int MAX_CODE_POINTS = 255;
    public static final String TARGET_COLLATION = "utf8mb4_0900_bin";
    public static final String FINAL_STATUS_TYPE = "enum('CLOSED','OPEN','DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN')";
    public static final String LEGACY_STATUS_TYPE = "enum('CLOSED','OPEN')";

    public enum Phase { BEFORE, EXPANDED, KEY_COLLATION, NOT_NULL, COMPLETE }

    public record Row(long topicId, String title, String existingKey) {
    }

    public record Schema(String statusType, boolean targetDateNullable, String keyCharacterSet,
                         String keyCollation, boolean keyNullable, boolean uniqueKeyPresent) {
    }

    public record Preflight(Map<Long, String> normalizedKeys) {
    }

    private TopicDraftV5Logic() {
    }

    public static Preflight preflight(List<Row> rows) {
        Map<Long, String> normalized = new LinkedHashMap<>();
        Map<String, Long> seen = new HashMap<>();
        for (Row row : rows) {
            String key;
            try {
                key = normalizeV1Title(row.title());
            } catch (RuntimeException invalidTitle) {
                throw new IllegalStateException("V5 title preflight rejected invalid title data", invalidTitle);
            }
            if (row.existingKey() != null && !row.existingKey().equals(key)) {
                throw new IllegalStateException("V5 title preflight found an existing normalized key mismatch");
            }
            Long firstId = seen.putIfAbsent(key, row.topicId());
            if (firstId != null) {
                throw new IllegalStateException("V5 title preflight found duplicate normalized title keys");
            }
            normalized.put(row.topicId(), key);
        }
        return new Preflight(Map.copyOf(normalized));
    }

    /** V5 이력은 배포 뒤 runtime normalizer가 바뀌어도 결과가 변하지 않도록 규칙을 고정합니다. */
    public static String normalizeV1Title(String rawTitle) {
        requireScalarString(rawTitle);
        String displayTitle = trimUnicodeWhiteSpace(rawTitle);
        if (displayTitle.isEmpty()) throw new IllegalArgumentException("title required");
        if (codePointCount(displayTitle) > MAX_CODE_POINTS) throw new IllegalArgumentException("title too long");

        String canonical = Normalizer.normalize(rawTitle, Normalizer.Form.NFC);
        StringBuilder key = new StringBuilder(canonical.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < canonical.length();) {
            int codePoint = canonical.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeWhiteSpace(codePoint)) {
                pendingSpace = key.length() > 0;
                continue;
            }
            if (pendingSpace) {
                key.append(' ');
                pendingSpace = false;
            }
            key.appendCodePoint(mapPunctuation(codePoint));
        }
        String normalizedTitle = key.toString();
        if (normalizedTitle.isEmpty()) throw new IllegalArgumentException("title required");
        if (codePointCount(normalizedTitle) > MAX_CODE_POINTS) throw new IllegalArgumentException("key too long");
        return normalizedTitle;
    }

    public static void requireBackfilled(List<Row> rows, Preflight preflight) {
        for (Row row : rows) {
            if (row.existingKey() == null || !row.existingKey().equals(preflight.normalizedKeys().get(row.topicId()))) {
                throw new IllegalStateException("V5 title backfill is incomplete or inconsistent");
            }
        }
    }

    public static Phase classify(Schema schema, String databaseDefaultCollation) {
        String status = normalizeType(schema.statusType());
        String oldStatus = normalizeType(LEGACY_STATUS_TYPE);
        String newStatus = normalizeType(FINAL_STATUS_TYPE);
        String charset = lower(schema.keyCharacterSet());
        String collation = lower(schema.keyCollation());
        String defaultCollation = lower(databaseDefaultCollation);
        if (!"utf8mb4".equals(charset) || defaultCollation == null || collation == null) {
            throw new IllegalStateException("V5 schema is outside a known retry prefix");
        }
        if (oldStatus.equals(status) && !schema.targetDateNullable() && !schema.uniqueKeyPresent()
                && schema.keyNullable() && defaultCollation.equals(collation)) return Phase.BEFORE;
        if (newStatus.equals(status) && schema.targetDateNullable() && !schema.uniqueKeyPresent()
                && schema.keyNullable() && defaultCollation.equals(collation)) return Phase.EXPANDED;
        if (newStatus.equals(status) && schema.targetDateNullable() && !schema.uniqueKeyPresent()
                && schema.keyNullable() && TARGET_COLLATION.equals(collation)) return Phase.KEY_COLLATION;
        if (newStatus.equals(status) && schema.targetDateNullable() && !schema.uniqueKeyPresent()
                && !schema.keyNullable() && TARGET_COLLATION.equals(collation)) return Phase.NOT_NULL;
        if (newStatus.equals(status) && schema.targetDateNullable() && schema.uniqueKeyPresent()
                && !schema.keyNullable() && TARGET_COLLATION.equals(collation)) return Phase.COMPLETE;
        throw new IllegalStateException("V5 schema is outside a known retry prefix");
    }

    private static String normalizeType(String type) {
        return type == null ? null : type.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void requireScalarString(String value) {
        if (value == null) throw new IllegalArgumentException("title required");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("invalid Unicode scalar");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("invalid Unicode scalar");
            }
        }
    }

    private static String trimUnicodeWhiteSpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhiteSpace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhiteSpace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00A0
                || codePoint == 0x1680 || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F
                || codePoint == 0x205F || codePoint == 0x3000;
    }

    private static int mapPunctuation(int codePoint) {
        return switch (codePoint) {
            case 0x2018, 0x2019 -> '\'';
            case 0x201C, 0x201D -> '"';
            case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014 -> '-';
            default -> codePoint;
        };
    }
}
