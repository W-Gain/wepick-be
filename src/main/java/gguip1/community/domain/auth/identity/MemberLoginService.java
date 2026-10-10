package gguip1.community.domain.auth.identity;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.Set;
import java.util.Objects;

/** UNIQUE 경합은 실패한 transaction 밖에서 다시 조회·실행합니다. */
@Service
public class MemberLoginService {
    private static final int MAX_TRANSACTION_ATTEMPTS = 5;
    private static final Set<String> RETRYABLE_UNIQUE_CONSTRAINTS = Set.of(
            "UK_social_accounts_provider_identity",
            "UK_social_accounts_user_provider",
            "UK2ty1xmrrgtn89xt7kyxx6ta7h");
    private final MemberLoginTransaction transaction;

    public MemberLoginService(MemberLoginTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    public MemberLoginResult login(KakaoIdentity identity, String anonymousTokenHash) {
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transaction.resolveAndMerge(identity, anonymousTokenHash);
            } catch (DataIntegrityViolationException conflict) {
                if (!isRetryableUniqueConflict(conflict)) {
                    throw new MemberLoginTransaction.MemberLoginFailure();
                }
                // provider 또는 무작위 nickname의 알려진 UNIQUE 경합만 새 transaction에서 재확인합니다.
            }
        }
        throw new MemberLoginTransaction.MemberLoginFailure();
    }

    private static boolean isRetryableUniqueConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraintName = normalizeConstraintName(violation.getConstraintName());
                if (constraintName != null && RETRYABLE_UNIQUE_CONSTRAINTS.contains(constraintName)) return true;
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                String message = sql.getMessage();
                if (message != null && RETRYABLE_UNIQUE_CONSTRAINTS.stream().anyMatch(message::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeConstraintName(String name) {
        if (name == null) return null;
        String withoutSchema = name.substring(name.lastIndexOf('.') + 1);
        return withoutSchema.replace("`", "");
    }
}
