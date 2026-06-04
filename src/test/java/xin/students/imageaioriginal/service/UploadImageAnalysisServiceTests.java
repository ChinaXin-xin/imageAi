package xin.students.imageaioriginal.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UploadImageAnalysisServiceTests {

    @Test
    void promptWithUserModelPrependsModelBeforeAnalysisPrompt() {
        String prompt = UploadImageAnalysisService.promptWithUserModel(
                "三星 S23 Ultra",
                "请深析实拍图结构。"
        );

        assertThat(prompt)
                .startsWith("用户输入机型：三星 S23 Ultra")
                .containsSubsequence("用户输入机型：三星 S23 Ultra", "请深析实拍图结构。");
    }
}
