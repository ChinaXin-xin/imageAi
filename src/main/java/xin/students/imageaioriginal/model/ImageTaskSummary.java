package xin.students.imageaioriginal.model;

import java.util.Map;

public record ImageTaskSummary(
        String id,
        String productName,
        String status,
        String statusText,
        String createdAt,
        String updatedAt,
        String thumbnail,
        String thumbnailName,
        Map<String, Integer> fileSummary,
        ImageTaskPayload form,
        Integer completedCount,
        Integer totalCount,
        String errorMessage
) {
}
