package gguip1.community.domain.topic.admin;

import com.fasterxml.jackson.databind.JsonNode;
import gguip1.community.global.response.ApiDataResponse;
import gguip1.community.global.security.CurrentActor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** target DRAFT 관리 API를 legacy 사용자 토픽 controller와 분리합니다. */
@RestController
@RequestMapping("/admin/topics")
public class AdminTopicDraftController {
    private final AdminTopicDraftService service;

    public AdminTopicDraftController(AdminTopicDraftService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiDataResponse<AdminTopicDraftViews.Detail>> create(@RequestBody JsonNode body) {
        var created = service.create(CurrentActor.requireUserId(), AdminTopicDraftJson.parseCreate(body));
        return ResponseEntity.created(URI.create("/api/admin/topics/" + created.id()))
                .header("Cache-Control", "private, no-store")
                .body(new ApiDataResponse<>(created));
    }

    @GetMapping
    public ResponseEntity<ApiDataResponse<AdminTopicDraftViews.Page>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(new ApiDataResponse<>(service.list(status, cursor, limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiDataResponse<AdminTopicDraftViews.Detail>> detail(@PathVariable("id") long id) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(new ApiDataResponse<>(service.detail(id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiDataResponse<AdminTopicDraftViews.Detail>> patch(
            @PathVariable("id") long id, @RequestBody JsonNode body) {
        var updated = service.patch(id, CurrentActor.requireUserId(), AdminTopicDraftJson.parsePatch(body));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(new ApiDataResponse<>(updated));
    }
}
