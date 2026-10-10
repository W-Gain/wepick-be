package gguip1.community.domain.post.controller;

import gguip1.community.domain.post.dto.response.PostCommentPageItemResponse;
import gguip1.community.domain.post.dto.response.PostCommentPageResponse;
import gguip1.community.domain.post.dto.request.PostCommentRequest;
import gguip1.community.domain.post.service.PostCommentService;
import gguip1.community.global.auth.annotation.Auth;
import gguip1.community.global.security.CurrentActor;
import gguip1.community.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PostCommentController {
    private final PostCommentService postCommentService;

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<PostCommentPageResponse>> getComments(@PathVariable Long postId,
                                                                            @RequestParam(required = false) Long lastCommentId,
                                                                            @RequestParam(defaultValue = "10") int size) {
        PostCommentPageResponse response = postCommentService.getLatestComments(CurrentActor.userIdOrNull(), postId, lastCommentId, size);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("Comments retrieved successfully", response));
    }

    @Auth
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<PostCommentPageItemResponse>> createComment(@PathVariable Long postId,
                                                                                  @RequestBody PostCommentRequest postCommentRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Comment created", postCommentService.createComment(CurrentActor.userIdOrNull(), postId, postCommentRequest)));
    }

    @Auth
    @PatchMapping("/posts/{postId}/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> updateComment(@PathVariable Long postId,
                                                           @PathVariable Long commentId,
                                                           @RequestBody PostCommentRequest postCommentRequest) {
        postCommentService.updateComment(CurrentActor.userIdOrNull(), postId, commentId, postCommentRequest);
        return ResponseEntity.noContent().build();
    }

    @Auth
    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(@PathVariable Long postId,
                                                           @PathVariable Long commentId) {
        postCommentService.deleteComment(CurrentActor.userIdOrNull(), postId, commentId);
        return ResponseEntity.noContent().build();
    }
}
