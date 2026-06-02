package xin.students.imageaioriginal.model;

public record ImageTaskResultView(
        Long id,
        String resultType,
        Integer itemIndex,
        Long parentResultId,
        Integer versionIndex,
        String status,
        String statusText,
        String prompt,
        String imageUrl,
        String imageBase64,
        String revisedPrompt,
        String editSuggestion,
        String errorMessage,
        String createdAt,
        String updatedAt
) {
}
