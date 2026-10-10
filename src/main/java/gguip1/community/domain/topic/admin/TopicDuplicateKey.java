package gguip1.community.domain.topic.admin;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** named normalized-title UNIQUE의 MySQL 1062만 도메인 중복으로 분류합니다. */
public final class TopicDuplicateKey {
    private static final String INDEX_NAME = "UK_topics_normalized_title";
    private static final Pattern MYSQL_KEY_TOKEN = Pattern.compile(
            "(?i)for key\\s+(?:'([^']+)'|`([^`]+)`|([^\\s]+))\\s*$");

    private TopicDuplicateKey() {
    }

    public static boolean isNormalizedTitleDuplicate(Throwable failure) {
        boolean namedConstraint = false;
        boolean mysqlDuplicate = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "UK_topics_normalized_title".equalsIgnoreCase(violation.getConstraintName())) {
                namedConstraint = true;
            }
            if (cause instanceof SQLException sqlException) {
                mysqlDuplicate |= sqlException.getErrorCode() == 1062;
                namedConstraint |= sqlException.getErrorCode() == 1062 && hasExactIndexToken(sqlException.getMessage());
            }
        }
        return namedConstraint && mysqlDuplicate;
    }

    private static boolean hasExactIndexToken(String message) {
        if (message == null) return false;
        Matcher matcher = MYSQL_KEY_TOKEN.matcher(message);
        if (!matcher.find()) return false;
        String token = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        String[] parts = token.split("\\.", -1);
        if (!INDEX_NAME.equals(parts[parts.length - 1])) return false;
        return switch (parts.length) {
            case 1 -> true;
            case 2 -> "topics".equalsIgnoreCase(parts[0]);
            case 3 -> "topics".equalsIgnoreCase(parts[1]);
            default -> false;
        };
    }
}
