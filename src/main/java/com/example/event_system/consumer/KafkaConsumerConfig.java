package com.example.event_system.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Consumer 전역 에러 핸들러 설정
     * 1. 시스템 장애 시 정해진 횟수만큼 재시도(BackOff)
     * 2. 재시도 실패 시 DLQ(Dead Letter Queue) 토픽으로 메시지 이동
     */
    @Bean
    public DefaultErrorHandler errorHandler() {
        // 1. Recoverer: 재시도 횟수 초과 시 실행될 로직 (DLQ 전송)
        // 기본적으로 "원본토픽명.DLT"로 전송됩니다. (예: event-apply-topic.DLT)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(Objects.requireNonNull(kafkaTemplate),
                (record, ex) -> {
                    log.error("### [DLQ 전송] Topic: {}, Partition: {}, Error: {}", 
                            record.topic(), record.partition(), ex.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });

        // 2. BackOff: 재시도 간격 및 횟수 설정
        // 1초 간격으로 최대 3회 시도 (최초 1회 + 재시도 2회)
        FixedBackOff backOff = new FixedBackOff(1000L, 2L);

        // 3. DefaultErrorHandler 등록
        return new DefaultErrorHandler(recoverer, backOff);
    }
}