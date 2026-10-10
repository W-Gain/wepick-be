package gguip1.community.domain.user.repository;

import gguip1.community.domain.user.entity.User;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@SQLRestriction("status = 0")
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);

    @Query(value = "SELECT * FROM users WHERE user_id = :userId AND status = 0 FOR UPDATE", nativeQuery = true)
    Optional<User> findActiveForUpdate(@Param("userId") long userId);
}
