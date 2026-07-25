package com.github.taskmasterbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseConfigurationTests {

    private static final Pattern TELEGRAM_TOKEN =
            Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{20,}");

    @Test
    void exampleConfigurationContainsOnlyPlaceholderTelegramCredentials()
            throws IOException {
        String example = read(".env.example");

        assertThat(example)
                .contains("TELEGRAM_BOT_USERNAME=your_bot_username")
                .contains("TELEGRAM_BOT_TOKEN=your_bot_token");
        assertThat(TELEGRAM_TOKEN.matcher(example).find()).isFalse();
    }

    @Test
    void localAndDockerDatasourceAddressesRemainDistinct() throws IOException {
        assertThat(read("src/main/resources/application.yml"))
                .contains("jdbc:postgresql://localhost:5433/taskmaster_db");
        assertThat(read("compose.yml"))
                .contains("jdbc:postgresql://postgres:5432/${POSTGRES_DB:-taskmaster_db}")
                .contains("\"${POSTGRES_PORT:-5433}:5432\"");
    }

    @Test
    void localEnvironmentFileIsIgnored() throws IOException {
        assertThat(read(".gitignore").lines())
                .anyMatch(line -> line.trim().equals(".env"));
    }

    private String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
