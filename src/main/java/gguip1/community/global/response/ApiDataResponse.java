package gguip1.community.global.response;

/** 목표 성공 봉투는 data만 담고 기존 message 필드는 사용하지 않습니다. */
public record ApiDataResponse<T>(T data) {
}
