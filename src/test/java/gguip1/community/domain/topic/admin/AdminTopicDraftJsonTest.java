package gguip1.community.domain.topic.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTopicDraftJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("등록 요청은 허용된 제목·카테고리·A/B만 서비스 command로 만든다")
    void parsesAcceptedCreateShape() throws Exception {
        var command = AdminTopicDraftJson.parseCreate(json.readTree("""
                {"title":"질문","categoryCode":"TASTE_DAILY","options":[
                  {"label":"A","text":"하나"},{"label":"B","text":"둘"}]}
                """));

        assertThat(command.title()).isEqualTo("질문");
        assertThat(command.options()).extracting(option -> option.label().name()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("PATCH는 필드 누락과 명시적 null을 구별하고 서버 관리 필드를 거부한다")
    void distinguishesMissingAndNullAndRejectsUnknownFields() throws Exception {
        var command = AdminTopicDraftJson.parsePatch(json.readTree("""
                {"expectedRevision":1,"categoryCode":"SOCIETY_TRENDS"}
                """));
        assertThat(command.titlePresent()).isFalse();
        assertThat(command.categoryPresent()).isTrue();

        assertInvalid("{\"expectedRevision\":1,\"title\":null}", "title", "INVALID_FORMAT");
        assertInvalid("{\"expectedRevision\":1,\"status\":\"PUBLISHED\",\"title\":\"x\"}",
                "status", "INVALID_FORMAT");
        assertInvalid("{\"expectedRevision\":null,\"title\":\"x\"}", "expectedRevision", "REQUIRED");
    }

    private void assertInvalid(String source, String field, String code) throws Exception {
        assertThatThrownBy(() -> AdminTopicDraftJson.parsePatch(json.readTree(source)))
                .isInstanceOf(AdminTopicDraftException.class)
                .satisfies(error -> {
                    var exception = (AdminTopicDraftException) error;
                    assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(exception.details()).containsKey("fields");
                    assertThat(exception.details().get("fields").toString()).contains(field, code);
                });
    }
}
