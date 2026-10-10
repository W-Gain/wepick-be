package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/** 빈 전환 DB만 허용한 뒤 Kakao 회원 스키마로 전환합니다. */
public class V4__kakao_members_and_anonymous_votes extends BaseJavaMigration {
    private static final List<String> APPLICATION_TABLES = List.of(
            "users", "images", "posts", "post_comments", "post_images", "post_likes", "post_stats",
            "topics", "topic_options", "votes", "social_accounts", "anonymous_voters",
            "login_attempts", "external_unlink_jobs", "topic_reviews", "topic_review_checks",
            "topic_status_events", "pick_assignments", "opinions", "opinion_likes",
            "opinion_moderation_events");

    @Override
    public void migrate(Context context) throws Exception {
        ensureApplicationDataIsEmpty(context);
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    ALTER TABLE users
                        DROP INDEX UK6dotkott2kjsp8vw4d0m25fb7,
                        DROP COLUMN email,
                        DROP COLUMN password,
                        MODIFY COLUMN nickname VARCHAR(30) NULL,
                        ADD CONSTRAINT CHK_users_active_nickname CHECK (status <> 0 OR nickname IS NOT NULL)
                    """);
            statement.execute("ALTER TABLE votes MODIFY COLUMN user_id BIGINT NULL");
        }
    }

    private static void ensureApplicationDataIsEmpty(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            for (String table : APPLICATION_TABLES) {
                try (ResultSet rows = statement.executeQuery("SELECT 1 FROM `" + table + "` LIMIT 1")) {
                    if (rows.next()) {
                        throw new FlywayException("V4 requires an empty application database; no data was changed");
                    }
                }
            }
        }
    }
}
