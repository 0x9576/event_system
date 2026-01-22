package com.example.event_system.Integration_Test;

import com.example.event_system.config.FakeRedisConfig;
import com.example.event_system.service.EventResultService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(FakeRedisConfig.class)
@EmbeddedKafka(
    partitions = 1,
    topics = {"event-apply-topic", "event-apply-topic.DLT"}
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "logging.level.org.apache.kafka=ERROR"
})
class KafkaDlqTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private EventResultService eventResultService;

    @Test
    @DisplayName("DLQ 테스트: 3회 재시도 실패 후 DLQ 토픽으로 메시지가 이동해야 한다")
    void testDlqRouting() {
        // Given
        String topic = "event-apply-topic";
        String dlqTopic = "event-apply-topic.DLT";
        String message = "1:100"; // eventId:memberId

        // Mock: 항상 예외 발생 (재시도 유발)
        doThrow(new RuntimeException("System Error"))
                .when(eventResultService).processWinning(anyLong(), anyLong());

        // When
        kafkaTemplate.send(topic, message);

        // Then
        // 1. 테스트용 Consumer 생성 (DLQ 토픽 구독)
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-dlq-group", "false", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        
        consumer.subscribe(Collections.singleton(dlqTopic));

        // 2. DLQ 메시지 수신 대기 및 검증 (최대 10초 대기)
        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, dlqTopic, Duration.ofMillis(10000));

        assertThat(record).isNotNull();
        assertThat(record.value()).isEqualTo(message);

        // 3. 재시도 횟수 검증 (최초 1회 + 재시도 2회 = 총 3회 호출)
        verify(eventResultService, times(3)).processWinning(anyLong(), anyLong());
        
        consumer.close();
    }
}