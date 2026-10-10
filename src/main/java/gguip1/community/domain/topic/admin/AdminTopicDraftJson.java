package gguip1.community.domain.topic.admin;

import com.fasterxml.jackson.databind.JsonNode;
import gguip1.community.domain.topic.entity.OptionLabel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** DTO 바인딩에서 누락과 명시적 null, 알 수 없는 필드를 구별해 검증합니다. */
public final class AdminTopicDraftJson {
    private static final Set<String> CREATE_FIELDS = Set.of("title", "categoryCode", "options");
    private static final Set<String> PATCH_FIELDS = Set.of("expectedRevision", "title", "categoryCode", "options");

    private AdminTopicDraftJson() {
    }

    public static AdminTopicDraftCommand.Create parseCreate(JsonNode body) {
        requireObject(body);
        rejectUnknown(body, CREATE_FIELDS);
        String title = requiredString(body, "title");
        String category = requiredString(body, "categoryCode");
        JsonNode optionsNode = body.get("options");
        if (optionsNode == null || optionsNode.isNull()) {
            throw AdminTopicDraftException.validation("options", "REQUIRED");
        }
        return new AdminTopicDraftCommand.Create(title, category, parseOptions(optionsNode, "options"));
    }

    public static AdminTopicDraftCommand.Patch parsePatch(JsonNode body) {
        requireObject(body);
        rejectUnknown(body, PATCH_FIELDS);
        JsonNode revision = body.get("expectedRevision");
        if (revision == null || revision.isNull()) {
            throw AdminTopicDraftException.validation("expectedRevision", "REQUIRED");
        }
        if (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue() < 1) {
            throw AdminTopicDraftException.validation("expectedRevision", "INVALID_FORMAT");
        }

        boolean titlePresent = body.has("title");
        String title = optionalString(body, "title", titlePresent);
        boolean categoryPresent = body.has("categoryCode");
        String category = optionalString(body, "categoryCode", categoryPresent);
        boolean optionsPresent = body.has("options");
        List<AdminTopicDraftCommand.Option> options = null;
        if (optionsPresent) {
            JsonNode optionsNode = body.get("options");
            if (optionsNode == null || optionsNode.isNull()) {
                throw AdminTopicDraftException.validation("options", "INVALID_FORMAT");
            }
            options = parseOptions(optionsNode, "options");
        }
        if (!titlePresent && !categoryPresent && !optionsPresent) {
            throw AdminTopicDraftException.validation("body", "REQUIRED");
        }
        return new AdminTopicDraftCommand.Patch(revision.intValue(), titlePresent, title,
                categoryPresent, category, optionsPresent, options);
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw AdminTopicDraftException.validation("body", "INVALID_FORMAT");
        }
    }

    private static void rejectUnknown(JsonNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw AdminTopicDraftException.validation(field, "INVALID_FORMAT");
            }
        });
    }

    private static String requiredString(JsonNode body, String field) {
        return textValue(body.get(field), field);
    }

    private static String requiredNestedString(JsonNode body, String field, String errorPath) {
        return textValue(body.get(field), errorPath);
    }

    private static String textValue(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            throw AdminTopicDraftException.validation(field, "REQUIRED");
        }
        if (!value.isTextual()) {
            throw AdminTopicDraftException.validation(field, "INVALID_FORMAT");
        }
        return value.textValue();
    }

    private static String optionalString(JsonNode body, String field, boolean present) {
        if (!present) return null;
        JsonNode value = body.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw AdminTopicDraftException.validation(field, "INVALID_FORMAT");
        }
        return value.textValue();
    }

    private static List<AdminTopicDraftCommand.Option> parseOptions(JsonNode value, String field) {
        if (!value.isArray() || value.size() != 2) {
            throw AdminTopicDraftException.validation(field, "INVALID_FORMAT");
        }
        List<AdminTopicDraftCommand.Option> options = new ArrayList<>(2);
        Set<OptionLabel> labels = new HashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode option = value.get(index);
            String optionPath = field + "[" + index + "]";
            if (!option.isObject()) throw AdminTopicDraftException.validation(optionPath, "INVALID_FORMAT");
            rejectUnknown(option, Set.of("label", "text"));
            String labelValue = requiredNestedString(option, "label", optionPath + ".label");
            OptionLabel label;
            try {
                label = OptionLabel.valueOf(labelValue);
            } catch (IllegalArgumentException invalidLabel) {
                throw AdminTopicDraftException.validation(optionPath + ".label", "INVALID_FORMAT");
            }
            if (!labels.add(label) || label != (index == 0 ? OptionLabel.A : OptionLabel.B)) {
                throw AdminTopicDraftException.validation(optionPath + ".label", "INVALID_FORMAT");
            }
            options.add(new AdminTopicDraftCommand.Option(label,
                    requiredNestedString(option, "text", optionPath + ".text")));
        }
        return List.copyOf(options);
    }
}
