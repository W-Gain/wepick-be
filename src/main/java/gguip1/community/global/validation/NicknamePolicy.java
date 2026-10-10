package gguip1.community.global.validation;

/** 회원 생성·수정·중복 확인에서 공유하는 닉네임 정규화와 형식 규칙입니다. */
public final class NicknamePolicy {
    public static final int MAX_CODE_POINTS = 30;

    private NicknamePolicy() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid nickname");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("Invalid nickname");
            }
            offset += Character.charCount(codePoint);
        }

        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        String normalized = value.substring(start, end);
        int count = normalized.codePointCount(0, normalized.length());
        if (count < 1 || count > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("Invalid nickname");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (isWhitespace(codePoint)) {
                throw new IllegalArgumentException("Invalid nickname");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    public static boolean isValid(String value) {
        if (value == null) return true;
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
