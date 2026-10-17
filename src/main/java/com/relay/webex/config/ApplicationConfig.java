package com.relay.webex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.webex.broker.InMemoryMessageBroker;
import com.relay.webex.domain.client.ClientDirectory;
import com.relay.webex.domain.messaging.MessageBroker;
import com.relay.webex.domain.messaging.MessagingService;
import com.relay.webex.domain.messaging.MessagingServiceImpl;
import com.relay.webex.orchestration.ClientConnection;
import com.relay.webex.protocol.FrameCodec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Socket;
import java.util.function.Function;

/**
 * Registers the property-bound configuration classes and the beans that
 * component scanning can't provide on its own: {@link ObjectMapper} (Jackson's
 * autoconfiguration doesn't fire without {@code spring-boot-starter-web}/
 * {@code -json} on the classpath), the bounded {@link InMemoryMessageBroker},
 * a {@link Socket}-to-{@link ClientConnection} factory ({@link ClientConnection}
 * is constructed per socket, not container-managed), and the plain
 * {@link MessagingServiceImpl.Limits}/{@link ClientDirectory.Settings} records
 * built from the {@code @ConfigurationProperties} classes below. Everything
 * else is component-scanned on the implementation classes themselves.
 */
@Configuration
@EnableConfigurationProperties({TransportProperties.class, StorageProperties.class, MessagingProperties.class,
        BrokerProperties.class})
public class ApplicationConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public MessageBroker messageBroker(BrokerProperties brokerProperties) {
        return new InMemoryMessageBroker(brokerProperties.getMaxQueueSize());
    }

    @Bean
    public Function<Socket, ClientConnection> clientConnectionFactory(
            FrameCodec codec, MessagingService messagingService, TransportProperties transportProperties) {
        return socket -> new ClientConnection(socket, codec, messagingService,
                transportProperties.getWriteQueueCapacity());
    }

    @Bean
    public MessagingServiceImpl.Limits messagingLimits(
            MessagingProperties messagingProperties, StorageProperties storageProperties) {
        return new MessagingServiceImpl.Limits(
                messagingProperties.getMaxPayloadBytes(), storageProperties.getMaxMailboxSize());
    }

    @Bean
    public ClientDirectory.Settings clientDirectorySettings(MessagingProperties messagingProperties) {
        return new ClientDirectory.Settings(messagingProperties.getDedupeWindowSize());
    }
}
