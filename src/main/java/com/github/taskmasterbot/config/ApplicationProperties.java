package com.github.taskmasterbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "application")
public record ApplicationProperties(ZoneId timeZone) {
}
