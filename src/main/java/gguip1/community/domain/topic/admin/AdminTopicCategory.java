package gguip1.community.domain.topic.admin;

import java.util.Arrays;

/** 관리자 DRAFT API가 허용하는 사용자 확정 카테고리 코드와 표시 이름입니다. */
public enum AdminTopicCategory {
    TASTE_DAILY("취향·일상"),
    VALUES_RELATIONSHIPS("가치관·관계"),
    SOCIETY_TRENDS("사회·트렌드"),
    HYPOTHETICAL_BALANCE("가상 상황·밸런스");

    private final String label;

    AdminTopicCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AdminTopicCategory fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst().orElse(null);
    }
}
