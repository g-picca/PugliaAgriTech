package it.puglia.agritech.consumer_service.configs;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.CredentialsProvider;
import com.rabbitmq.client.impl.CredentialsRefreshService;
import com.rabbitmq.client.impl.DefaultCredentialsRefreshService;
import com.rabbitmq.client.impl.OAuth2ClientCredentialsGrantCredentialsProvider;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione della connessione a RabbitMQ tramite OAuth2.
 * Si autentica tramite un token JWT recuperato da Keycloak.
 * Il token viene rinnovato automaticamente prima della scadenza.
 */
@Configuration
public class RabbitMqOAuth2Config {
    @Value("${agritech.keycloak.token-uri}")
    private String tokenUri;
    @Value("${agritech.keycloak.client-id}")
    private String clientId;
    @Value("${agritech.keycloak.client-secret}")
    private String clientSecret;
    @Value("${spring.rabbitmq.host}")
    private String rabbitHost;
    @Value("${spring.rabbitmq.port}")
    private int rabbitPort;

    /**
     * Il provider di credenziali OAuth2.
     */
    @Bean
    public CredentialsProvider oauth2CredentialsProvider() {
        return new OAuth2ClientCredentialsGrantCredentialsProvider
                .OAuth2ClientCredentialsGrantCredentialsProviderBuilder()
                .tokenEndpointUri(tokenUri)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("client_credentials")
                .parameter("scope", "rabbitmq.read:*/*")
                .build();
    }

    /**
     * Il servizio di refresh automatico che mantiene la connessione sempre attiva.
     */
    @Bean
    public CredentialsRefreshService credentialsRefreshService() {
        return new DefaultCredentialsRefreshService
                .DefaultCredentialsRefreshServiceBuilder()
                .refreshDelayStrategy(
                        DefaultCredentialsRefreshService.ratioRefreshDelayStrategy(0.8)
                )
                .build();
    }

    /**
     * La ConnectionFactory di Spring AMQP, configurata per usare il provider OAuth2 ed il servizio di refresh
     */
    @Bean
    public CachingConnectionFactory connectionFactory(
            CredentialsProvider credentialsProvider,
            CredentialsRefreshService refreshService
    ) throws Exception {
        ConnectionFactory nativeFactory = new ConnectionFactory();
        nativeFactory.setHost(rabbitHost);
        nativeFactory.setPort(rabbitPort);
        nativeFactory.setCredentialsProvider(credentialsProvider);
        nativeFactory.setCredentialsRefreshService(refreshService);

        return new CachingConnectionFactory(nativeFactory);
    }

    /**
     * Container factory dedicato ai listener che gestiscono l'ack/nack
     * manualmente (necessario per instradare in DLQ solo i messaggi che
     * falliscono la verifica di firma/autorizzazione, senza scartare
     * l'intera coda in caso di errore).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory manualAckContainerFactory(
            CachingConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
