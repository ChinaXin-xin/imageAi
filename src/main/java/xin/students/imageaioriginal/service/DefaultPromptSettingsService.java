package xin.students.imageaioriginal.service;

import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.model.DefaultPromptSettings;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Service
public class DefaultPromptSettingsService {

    private static final String DEFAULT_MAIN_PROMPT =
            "生成高转化电商主图：突出手机膜产品质感、包装完整度和平台风格，画面干净高级，主体清晰，适合跨境电商首图。";
    private static final String DEFAULT_INTRO_PROMPT =
            "生成产品介绍图：围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点进行模块化展示，信息层级清晰，适合详情页。";

    private final DataSource dataSource;

    public DefaultPromptSettingsService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DefaultPromptSettings getSettings() {
        ensureTable();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select main_prompt, intro_prompt from default_prompt_settings where id = 1"
             );
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return new DefaultPromptSettings(
                        resultSet.getString("main_prompt"),
                        resultSet.getString("intro_prompt")
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取默认提示词失败", ex);
        }
        return saveSettings(new DefaultPromptSettings(DEFAULT_MAIN_PROMPT, DEFAULT_INTRO_PROMPT));
    }

    public DefaultPromptSettings saveSettings(DefaultPromptSettings settings) {
        ensureTable();
        String mainPrompt = normalize(settings.mainPrompt(), DEFAULT_MAIN_PROMPT);
        String introPrompt = normalize(settings.introPrompt(), DEFAULT_INTRO_PROMPT);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into default_prompt_settings (id, main_prompt, intro_prompt)
                     values (1, ?, ?)
                     on duplicate key update
                       main_prompt = values(main_prompt),
                       intro_prompt = values(intro_prompt),
                       updated_at = current_timestamp
                     """)) {
            statement.setString(1, mainPrompt);
            statement.setString(2, introPrompt);
            statement.executeUpdate();
            return new DefaultPromptSettings(mainPrompt, introPrompt);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存默认提示词失败", ex);
        }
    }

    private void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists default_prompt_settings (
                      id bigint primary key,
                      main_prompt text not null,
                      intro_prompt text not null,
                      updated_at timestamp not null default current_timestamp on update current_timestamp
                    )
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化默认提示词表失败", ex);
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
