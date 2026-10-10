package gguip1.community.domain.topic.admin;

import java.util.List;

/** 관리자 API 응답 모양을 legacy topic 응답과 분리합니다. */
public final class AdminTopicDraftViews {
    private AdminTopicDraftViews() {
    }

    public record Category(String code, String label) {
    }

    public record Option(String label, String text) {
    }

    public record ListItem(long id, String title, Category category, int contentRevision, String status) {
    }

    public record Detail(long id, String title, Category category, List<Option> options,
                         int contentRevision, String status, String scheduledKstDate,
                         String publishedAt, Long createdByUserId, String createdAt) {
    }

    public record Mutation(long id, int contentRevision, String status, String scheduledKstDate) {
    }

    public record Page(List<ListItem> items, String nextCursor) {
    }
}
