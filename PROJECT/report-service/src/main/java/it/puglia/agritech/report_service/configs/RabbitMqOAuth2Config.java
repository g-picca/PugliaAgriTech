package it.puglia.agritech.report_service.configs;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.CredentialsProvider;
import com.rabbitmq.client.impl.CredentialsRefreshService;
import com.rabbitmq.client.impl.DefaultCredentialsRefreshService;
import com.rabbitmq.client.impl.OAuth2ClientCredentialsGrantCredentialsProvider;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione della connessione a RabbitMQ tramite OAuth2, analoga a
 * quella del consumer-service ma con il client Keycloak 'consumer-report'.
 * Il report-service non ha bisogno di un container factory dedicato: non
 * dichiara nessun @RabbitListener, perché la ricezione della risposta
 * (reply-to dinamico) è gestita internamente da RabbitTemplate.
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

    @Bean
    public CredentialsProvider oauth2CredentialsProvider() {
        return new OAuth2ClientCredentialsGrantCredentialsProvider
                .OAuth2ClientCredentialsGrantCredentialsProviderBuilder()
                .tokenEndpointUri(tokenUri)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("client_credentials")
                .parameter("scope", "rabbitmq.write:*/*")
                .build();
    }

    @Bean
    public CredentialsRefreshService credentialsRefreshService() {
        return new DefaultCredentialsRefreshService
                .DefaultCredentialsRefreshServiceBuilder()
                .refreshDelayStrategy(
                        DefaultCredentialsRefreshService.ratioRefreshDelayStrategy(0.8)
                )
                .build();
    }

    @Bean
    public CachingConnectionFactory connectionFactory(
            CredentialsProvider credentialsProvider,
            CredentialsRefreshService refreshService
    ) {
        ConnectionFactory nativeFactory = new ConnectionFactory();
        nativeFactory.setHost(rabbitHost);
        nativeFactory.setPort(rabbitPort);
        nativeFactory.setCredentialsProvider(credentialsProvider);
        nativeFactory.setCredentialsRefreshService(refreshService);

        return new CachingConnectionFactory(nativeFactory);
    }

    /**
     * Timeout esplicito sulla receive: senza, una richiesta senza risposta
     * (consumer-service non raggiungibile, sovraccarico, bug) bloccherebbe
     * il thread chiamante a tempo indeterminato. Il controller REST traduce
     * un timeout (risposta null) in un errore HTTP esplicito verso il
     * frontend, invece di un hang silenzioso.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setReplyTimeout(5000);
        return template;
    }
}
