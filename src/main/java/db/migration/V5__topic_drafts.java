package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 제목 전체 사전검증 뒤에만 topic DRAFT 스키마를 단계별로 확장합니다. */
public class V5__topic_drafts extends BaseJavaMigration {
    private static final String STATUS_ENUM = "ENUM ('CLOSED','OPEN','DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN')";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String defaultCollation = databaseDefaultCollation(connection);
        TopicDraftV5Logic.Schema schema = readSchema(connection);
        TopicDraftV5Logic.Phase phase = classify(schema, defaultCollation);
        TopicDraftV5Logic.Preflight preflight = preflight(readRows(connection));

        if (phase == TopicDraftV5Logic.Phase.BEFORE) {
            execute(connection, "ALTER TABLE topics MODIFY COLUMN status " + STATUS_ENUM
                    + " NOT NULL, MODIFY COLUMN target_date DATE NULL");
            phase = classify(readSchema(connection), defaultCollation);
        }
        if (phase == TopicDraftV5Logic.Phase.EXPANDED) {
            execute(connection, "ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL");
            phase = classify(readSchema(connection), defaultCollation);
        }
        if (phase == TopicDraftV5Logic.Phase.KEY_COLLATION) {
            backfillMissingKeys(connection, preflight);
            List<TopicDraftV5Logic.Row> rows = readRows(connection);
            TopicDraftV5Logic.Preflight verified = preflight(rows);
            TopicDraftV5Logic.requireBackfilled(rows, verified);
            execute(connection, "ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL");
            phase = classify(readSchema(connection), defaultCollation);
        }
        if (phase == TopicDraftV5Logic.Phase.NOT_NULL) {
            List<TopicDraftV5Logic.Row> rows = readRows(connection);
            TopicDraftV5Logic.Preflight verified = preflight(rows);
            TopicDraftV5Logic.requireBackfilled(rows, verified);
            execute(connection, "ALTER TABLE topics ADD CONSTRAINT UK_topics_normalized_title UNIQUE (normalized_title)");
            phase = classify(readSchema(connection), defaultCollation);
        }
        if (phase != TopicDraftV5Logic.Phase.COMPLETE) {
            throw new FlywayException("V5 did not reach the expected complete schema prefix");
        }
        List<TopicDraftV5Logic.Row> finalRows = readRows(connection);
        TopicDraftV5Logic.requireBackfilled(finalRows, preflight(finalRows));
    }

    private static TopicDraftV5Logic.Preflight preflight(List<TopicDraftV5Logic.Row> rows) {
        try {
            return TopicDraftV5Logic.preflight(rows);
        } catch (RuntimeException failure) {
            throw new FlywayException(failure.getMessage(), failure);
        }
    }

    private static TopicDraftV5Logic.Phase classify(TopicDraftV5Logic.Schema schema, String defaultCollation) {
        try {
            return TopicDraftV5Logic.classify(schema, defaultCollation);
        } catch (RuntimeException failure) {
            throw new FlywayException(failure.getMessage(), failure);
        }
    }

    private static String databaseDefaultCollation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                     + "WHERE SCHEMA_NAME = DATABASE()")) {
            if (!result.next()) throw new FlywayException("V5 cannot read the database default collation");
            return result.getString(1);
        }
    }

    private static TopicDraftV5Logic.Schema readSchema(Connection connection) throws Exception {
        Column status = column(connection, "status");
        Column targetDate = column(connection, "target_date");
        Column normalized = column(connection, "normalized_title");
        if (!"enum".equalsIgnoreCase(status.dataType()) || status.nullable()
                || !"date".equalsIgnoreCase(targetDate.dataType())
                || !"varchar".equalsIgnoreCase(normalized.dataType()) || normalized.length() != 255) {
            throw new FlywayException("V5 found a topic column outside a known schema prefix");
        }
        boolean unique = readNamedUniqueIndex(connection);
        return new TopicDraftV5Logic.Schema(status.columnType(), targetDate.nullable(), normalized.characterSet(),
                normalized.collation(), normalized.nullable(), unique);
    }

    private static Column column(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME,
                       CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topics' AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new FlywayException("V5 required topic column is missing: " + name);
                return new Column(result.getString("DATA_TYPE"), result.getString("COLUMN_TYPE"),
                        "YES".equalsIgnoreCase(result.getString("IS_NULLABLE")),
                        result.getString("CHARACTER_SET_NAME"), result.getString("COLLATION_NAME"),
                        result.getInt("CHARACTER_MAXIMUM_LENGTH"));
            }
        }
    }

    private static boolean readNamedUniqueIndex(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topics'
                  AND INDEX_NAME = 'UK_topics_normalized_title'
                ORDER BY SEQ_IN_INDEX
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                if (result.getInt("NON_UNIQUE") != 0 || result.getInt("SEQ_IN_INDEX") != 1
                        || !"normalized_title".equalsIgnoreCase(result.getString("COLUMN_NAME")) || result.next()) {
                    throw new FlywayException("V5 found a malformed normalized-title index");
                }
                return true;
            }
        }
    }

    private static List<TopicDraftV5Logic.Row> readRows(Connection connection) throws Exception {
        List<TopicDraftV5Logic.Row> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT topic_id,title,normalized_title FROM topics ORDER BY topic_id")) {
            while (result.next()) {
                rows.add(new TopicDraftV5Logic.Row(result.getLong("topic_id"), result.getString("title"),
                        result.getString("normalized_title")));
            }
        }
        return rows;
    }

    private static void backfillMissingKeys(Connection connection, TopicDraftV5Logic.Preflight preflight) throws Exception {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE topics SET normalized_title = ? WHERE topic_id = ? AND normalized_title IS NULL")) {
            for (var entry : preflight.normalizedKeys().entrySet()) {
                update.setString(1, entry.getValue());
                update.setLong(2, entry.getKey());
                update.executeUpdate();
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Column(String dataType, String columnType, boolean nullable, String characterSet,
                          String collation, int length) {
    }
}
