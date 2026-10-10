package gguip1.community.domain.auth.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 회원 우선 규칙으로 익명 표를 한 트랜잭션에서 이관하거나 제거합니다. */
@Repository
public class AnonymousVoteMergeService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnonymousVoteMergeService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public boolean merge(long userId, String anonymousTokenHash) {
        if (anonymousTokenHash == null) return false;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        List<AnonymousIdentity> identities = jdbc.query("""
                SELECT anonymous_voter_id
                FROM anonymous_voters
                WHERE token_hash = ? AND expires_at > ?
                FOR UPDATE
                """, (rs, row) -> new AnonymousIdentity(rs.getLong("anonymous_voter_id")), anonymousTokenHash, now);
        if (identities.isEmpty()) return false;
        AnonymousIdentity identity = identities.getFirst();

        List<VoteRow> votes = jdbc.query("""
                SELECT vote_id, topic_id, option_id, user_id, anonymous_voter_id
                FROM votes
                WHERE user_id = ? OR anonymous_voter_id = ?
                ORDER BY topic_id, vote_id
                FOR UPDATE
                """, (rs, row) -> new VoteRow(rs.getLong("vote_id"), rs.getLong("topic_id"),
                rs.getLong("option_id"), (Long) rs.getObject("user_id"),
                (Long) rs.getObject("anonymous_voter_id")), userId, identity.id());

        Map<Long, VoteRow> memberVoteByTopic = votes.stream()
                .filter(vote -> Objects.equals(vote.userId(), userId))
                .collect(Collectors.toMap(VoteRow::topicId, vote -> vote));
        List<VoteRow> anonymousVotes = votes.stream()
                .filter(vote -> Objects.equals(vote.anonymousVoterId(), identity.id()))
                .toList();

        List<VoteRow> duplicates = anonymousVotes.stream()
                .filter(vote -> memberVoteByTopic.containsKey(vote.topicId()))
                .toList();
        lockOptions(duplicates);
        for (VoteRow duplicate : duplicates) {
            int deleted = jdbc.update("DELETE FROM votes WHERE vote_id = ? AND user_id IS NULL AND anonymous_voter_id = ?",
                    duplicate.voteId(), identity.id());
            if (deleted != 1) throw new IllegalStateException("Anonymous vote changed during login merge");
            int decremented = jdbc.update("UPDATE topic_options SET vote_count = vote_count - 1 WHERE option_id = ? AND vote_count > 0",
                    duplicate.optionId());
            if (decremented != 1) throw new IllegalStateException("Vote counter could not be decremented");
        }

        Set<Long> duplicateIds = duplicates.stream().map(VoteRow::voteId).collect(Collectors.toSet());
        for (VoteRow vote : anonymousVotes) {
            if (duplicateIds.contains(vote.voteId())) continue;
            int moved = jdbc.update("""
                    UPDATE votes SET user_id = ?, anonymous_voter_id = NULL
                    WHERE vote_id = ? AND user_id IS NULL AND anonymous_voter_id = ?
                    """, userId, vote.voteId(), identity.id());
            if (moved != 1) throw new IllegalStateException("Anonymous vote could not be linked");
        }

        int linked = jdbc.update("UPDATE anonymous_voters SET linked_user_id = ? WHERE anonymous_voter_id = ?",
                userId, identity.id());
        if (linked != 1) throw new IllegalStateException("Anonymous identity could not be linked");
        return !duplicates.isEmpty();
    }

    private void lockOptions(List<VoteRow> duplicates) {
        if (duplicates.isEmpty()) return;
        List<Long> optionIds = duplicates.stream().map(VoteRow::optionId).distinct().sorted().toList();
        String placeholders = optionIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT option_id FROM topic_options WHERE option_id IN (" + placeholders
                + ") ORDER BY option_id FOR UPDATE", (rs, row) -> rs.getLong(1), optionIds.toArray());
    }

    private record AnonymousIdentity(long id) { }
    private record VoteRow(long voteId, long topicId, long optionId, Long userId, Long anonymousVoterId) { }
}
