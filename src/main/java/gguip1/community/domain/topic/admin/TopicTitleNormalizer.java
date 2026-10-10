package gguip1.community.domain.topic.admin;

import java.text.Normalizer;

/** DRAFT와 legacy 경로가 공유하는 제목 표시값·중복 키 규칙입니다. */
public final class TopicTitleNormalizer {
    private static final int MAX_CODE_POINTS = 255;

    public record Result(String displayTitle, String normalizedTitle) {
    }

    public enum Violation {
        REQUIRED, TOO_LONG, INVALID_FORMAT
    }

    public static final class InvalidTitleException extends IllegalArgumentException {
        private final Violation violation;

        public InvalidTitleException(Violation violation) {
            super(violation.name());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }

    private TopicTitleNormalizer() {
    }

    public static Result normalize(String rawTitle) {
        requireScalarString(rawTitle);
        String displayTitle = trimUnicodeWhiteSpace(rawTitle);
        if (displayTitle.isEmpty()) {
            throw new InvalidTitleException(Violation.REQUIRED);
        }
        if (codePointCount(displayTitle) > MAX_CODE_POINTS) {
            throw new InvalidTitleException(Violation.TOO_LONG);
        }

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
        if (normalizedTitle.isEmpty()) {
            throw new InvalidTitleException(Violation.REQUIRED);
        }
        if (codePointCount(normalizedTitle) > MAX_CODE_POINTS) {
            throw new InvalidTitleException(Violation.TOO_LONG);
        }
        return new Result(displayTitle, normalizedTitle);
    }

    public static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    /** 옵션 본문도 동일한 scalar·코드 포인트 경계를 쓰되 제목 키 규칙은 적용하지 않습니다. */
    public static String validateOptionText(String rawText) {
        requireScalarString(rawText);
        String displayText = trimUnicodeWhiteSpace(rawText);
        if (displayText.isEmpty()) {
            throw new InvalidTitleException(Violation.REQUIRED);
        }
        if (codePointCount(displayText) > MAX_CODE_POINTS) {
            throw new InvalidTitleException(Violation.TOO_LONG);
        }
        return displayText;
    }

    private static void requireScalarString(String value) {
        if (value == null) {
            throw new InvalidTitleException(Violation.REQUIRED);
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new InvalidTitleException(Violation.INVALID_FORMAT);
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new InvalidTitleException(Violation.INVALID_FORMAT);
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
