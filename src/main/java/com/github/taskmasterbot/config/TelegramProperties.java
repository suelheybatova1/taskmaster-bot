package com.github.taskmasterbot.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramProperties(
        @NotBlank String username,
        @NotBlank String token
) {
}
