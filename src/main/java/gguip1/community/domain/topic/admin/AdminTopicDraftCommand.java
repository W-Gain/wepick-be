package gguip1.community.domain.topic.admin;

import gguip1.community.domain.topic.entity.OptionLabel;

import java.util.List;

/** HTTP JSON을 검증한 뒤 서비스에 전달하는 target DRAFT 입력입니다. */
public final class AdminTopicDraftCommand {
    private AdminTopicDraftCommand() {
    }

    public record Option(OptionLabel label, String text) {
    }

    public record Create(String title, String categoryCode, List<Option> options) {
    }

    public record Patch(int expectedRevision, boolean titlePresent, String title,
                        boolean categoryPresent, String categoryCode,
                        boolean optionsPresent, List<Option> options) {
    }
}
