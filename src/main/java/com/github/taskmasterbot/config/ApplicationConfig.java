package com.github.taskmasterbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties.class)
public class ApplicationConfig {

    @Bean
    TelegramClient telegramClient(TelegramProperties properties) {
        return new OkHttpTelegramClient(properties.token());
    }
}
