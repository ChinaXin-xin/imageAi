package xin.students.imageaioriginal.model;

import java.util.List;

public record DefaultPromptSettings(
        String mainPrompt,
        String introPrompt,
        String analysisPrompt,
        List<String> customSellingPoints
) {
}
