package com.example.event_system.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import redis.embedded.RedisServer;
import java.io.IOException;

@TestConfiguration
public class EmbeddedRedisConfig {

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() {
        try {
            // 포트 6379에서 Redis 시작
            redisServer = new RedisServer(6379);
            redisServer.start();
            System.out.println("### [Embedded Redis] 서버가 성공적으로 시작되었습니다. (Port: 6379)");
        } catch (IOException e) {
            throw new RuntimeException("Embedded Redis 시작 중 오류가 발생했습니다.", e);
        }
    }

    @PreDestroy
    public void stopRedis() {
        try{
            if (redisServer != null) {
                redisServer.stop();
                System.out.println("### [Embedded Redis] 서버가 종료되었습니다.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Embedded Redis 시작 중 오류가 발생했습니다.", e);
        }
    }
}