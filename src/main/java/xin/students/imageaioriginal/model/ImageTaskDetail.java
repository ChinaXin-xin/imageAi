package xin.students.imageaioriginal.model;

import java.util.List;
import java.util.Map;

public record ImageTaskDetail(
        String id,
        String productName,
        String status,
        String statusText,
        String createdAt,
        String updatedAt,
        String startedAt,
        String completedAt,
        String thumbnail,
        String thumbnailName,
        Map<String, Integer> fileSummary,
        ImageTaskPayload form,
        List<ImageTaskKitSpec> kitSpecs,
        Map<String, String> analysis,
        String finalMainPrompt,
        String finalIntroPrompt,
        List<ImageTaskResultView> results,
        Integer completedCount,
        Integer totalCount,
        String errorMessage
) {
}
