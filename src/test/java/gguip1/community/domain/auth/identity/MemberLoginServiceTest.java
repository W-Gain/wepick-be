package gguip1.community.domain.auth.identity;

import gguip1.community.domain.user.entity.UserRole;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberLoginServiceTest {
    private static final KakaoIdentity IDENTITY = new KakaoIdentity(123456L);
    private static final MemberLoginResult RESULT = new MemberLoginResult(42L, UserRole.USER, false);

    @Test
    @DisplayName("허용한 provider·nickname 고유 충돌만 새 트랜잭션으로 재시도한다")
    void retriesOnlyKnownIdentityAndNicknameUniqueConstraints() {
        for (String constraint : List.of(
                "UK_social_accounts_provider_identity",
                "UK_social_accounts_user_provider",
                "UK2ty1xmrrgtn89xt7kyxx6ta7h")) {
            MemberLoginTransaction transaction = mock(MemberLoginTransaction.class);
            MemberLoginService service = new MemberLoginService(transaction);
            when(transaction.resolveAndMerge(IDENTITY, null))
                    .thenThrow(uniqueViolation(constraint))
                    .thenReturn(RESULT);

            assertThat(service.login(IDENTITY, null)).isEqualTo(RESULT);
            verify(transaction, times(2)).resolveAndMerge(IDENTITY, null);
        }
    }

    @Test
    @DisplayName("외래 키·검사 제약과 알 수 없는 고유 제약은 즉시 실패한다")
    void doesNotRetryOtherIntegrityViolations() {
        for (String constraint : List.of("FK_social_accounts_user", "CHK_users_active_nickname", "UK_other")) {
            MemberLoginTransaction transaction = mock(MemberLoginTransaction.class);
            MemberLoginService service = new MemberLoginService(transaction);
            when(transaction.resolveAndMerge(IDENTITY, null)).thenThrow(uniqueViolation(constraint));

            assertThatThrownBy(() -> service.login(IDENTITY, null))
                    .isInstanceOf(MemberLoginTransaction.MemberLoginFailure.class);
            verify(transaction, times(1)).resolveAndMerge(IDENTITY, null);
        }
    }

    @Test
    @DisplayName("MySQL 1062는 허용한 인덱스 이름만 재시도한다")
    void retriesOnlyKnownMysqlDuplicateIndexes() {
        String knownIndex = "social_accounts.UK_social_accounts_provider_identity";
        MemberLoginTransaction transaction = mock(MemberLoginTransaction.class);
        MemberLoginService service = new MemberLoginService(transaction);
        when(transaction.resolveAndMerge(IDENTITY, null))
                .thenThrow(mysqlDuplicate(knownIndex))
                .thenReturn(RESULT);

        assertThat(service.login(IDENTITY, null)).isEqualTo(RESULT);
        verify(transaction, times(2)).resolveAndMerge(IDENTITY, null);

        MemberLoginTransaction unknownTransaction = mock(MemberLoginTransaction.class);
        MemberLoginService unknownService = new MemberLoginService(unknownTransaction);
        when(unknownTransaction.resolveAndMerge(IDENTITY, null)).thenThrow(mysqlDuplicate("UK_other"));
        assertThatThrownBy(() -> unknownService.login(IDENTITY, null))
                .isInstanceOf(MemberLoginTransaction.MemberLoginFailure.class);
        verify(unknownTransaction, times(1)).resolveAndMerge(IDENTITY, null);
    }

    @Test
    @DisplayName("허용 고유 충돌은 최대 다섯 번만 시도한다")
    void stopsRetryingKnownUniqueConflictAfterFiveAttempts() {
        MemberLoginTransaction transaction = mock(MemberLoginTransaction.class);
        MemberLoginService service = new MemberLoginService(transaction);
        var conflict = uniqueViolation("UK_social_accounts_provider_identity");
        when(transaction.resolveAndMerge(IDENTITY, null))
                .thenThrow(conflict).thenThrow(conflict).thenThrow(conflict).thenThrow(conflict).thenThrow(conflict);

        assertThatThrownBy(() -> service.login(IDENTITY, null))
                .isInstanceOf(MemberLoginTransaction.MemberLoginFailure.class);
        verify(transaction, times(5)).resolveAndMerge(IDENTITY, null);
    }

    @Test
    @DisplayName("이름이 없는 제약 충돌은 재시도하지 않고 안전하게 실패한다")
    void doesNotRetryIntegrityViolationWithoutConstraintName() {
        MemberLoginTransaction transaction = mock(MemberLoginTransaction.class);
        MemberLoginService service = new MemberLoginService(transaction);
        when(transaction.resolveAndMerge(IDENTITY, null)).thenThrow(uniqueViolation(null));

        assertThatThrownBy(() -> service.login(IDENTITY, null))
                .isInstanceOf(MemberLoginTransaction.MemberLoginFailure.class);
        verify(transaction, times(1)).resolveAndMerge(IDENTITY, null);
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        SQLException sql = new SQLException("database constraint violation", "23000", 1062);
        ConstraintViolationException hibernate = new ConstraintViolationException("database constraint violation", sql, constraint);
        return new DataIntegrityViolationException("database constraint violation", hibernate);
    }

    private static DataIntegrityViolationException mysqlDuplicate(String index) {
        SQLException sql = new SQLException("Duplicate entry 'test' for key '" + index + "'", "23000", 1062);
        return new DataIntegrityViolationException("duplicate key", sql);
    }
}
