package gguip1.community.domain.topic.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 관리자 초안 목록에서 UTC DATETIME(6)과 ID tie-break를 보존하는 전용 cursor입니다. */
public final class AdminTopicDraftCursor {
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern UTC_MICROS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> FIELDS = Set.of("v", "scope", "status", "createdAt", "topicId");

    public record Position(String status, LocalDateTime createdAtUtc, long topicId) {
    }

    private AdminTopicDraftCursor() {
    }

    public static String encode(Position position) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("scope", "admin-topics");
        payload.put("status", position.status());
        payload.put("createdAt", UTC_FORMAT.format(position.createdAtUtc()));
        payload.put("topicId", position.topicId());
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
        } catch (Exception impossible) {
            throw new IllegalStateException("Cursor JSON encoding failed", impossible);
        }
    }

    public static Position decode(String token, String requestedStatus) {
        try {
            if (token == null || !BASE64URL.matcher(token).matches()) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null || !root.isObject() || root.size() != FIELDS.size()) throw new IllegalArgumentException();
            var names = root.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new IllegalArgumentException();
            if (!root.path("v").isIntegralNumber() || !BigInteger.ONE.equals(root.path("v").bigIntegerValue())
                    || !root.path("scope").isTextual() || !"admin-topics".equals(root.path("scope").textValue())) {
                throw new IllegalArgumentException();
            }
            JsonNode statusNode = root.get("status");
            String status = statusNode == null || statusNode.isNull() ? null
                    : statusNode.isTextual() ? statusNode.textValue() : invalid();
            if (!java.util.Objects.equals(status, requestedStatus)) throw new IllegalArgumentException();
            if (!root.path("createdAt").isTextual()) throw new IllegalArgumentException();
            String createdAt = root.path("createdAt").textValue();
            if (!UTC_MICROS.matcher(createdAt).matches()) throw new IllegalArgumentException();
            LocalDateTime timestamp = LocalDateTime.parse(createdAt, UTC_FORMAT);
            if (timestamp.getYear() < 1000 || timestamp.getYear() > 9999) throw new IllegalArgumentException();
            JsonNode idNode = root.get("topicId");
            if (idNode == null || !idNode.isIntegralNumber()) throw new IllegalArgumentException();
            BigInteger id = idNode.bigIntegerValue();
            if (id.signum() <= 0 || id.compareTo(BigInteger.valueOf(MAX_SAFE_ID)) > 0) throw new IllegalArgumentException();
            return new Position(status, timestamp, id.longValueExact());
        } catch (CharacterCodingException | DateTimeParseException | IllegalArgumentException exception) {
            throw AdminTopicDraftException.invalidCursor();
        } catch (Exception exception) {
            throw AdminTopicDraftException.invalidCursor();
        }
    }

    private static String invalid() {
        throw new IllegalArgumentException();
    }
}
