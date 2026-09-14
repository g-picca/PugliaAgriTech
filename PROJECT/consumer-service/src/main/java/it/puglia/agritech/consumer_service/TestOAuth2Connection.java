package it.puglia.agritech.consumer_service;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.CredentialsProvider;
import com.rabbitmq.client.impl.CredentialsRefreshService;
import com.rabbitmq.client.impl.DefaultCredentialsRefreshService;
import com.rabbitmq.client.impl.OAuth2ClientCredentialsGrantCredentialsProvider;

/**
 * Test standalone con refresh service: verifica la connessione AMQP OAuth2
 * usando il client nativo RabbitMQ, senza passare da Spring Boot. Usato
 * durante la diagnosi del problema OAuth2/AMQP per isolare la causa (client
 * nativo vs configurazione Spring), e poi per verificare separatamente le
 * due correzioni trovate (kid della chiave di firma, formato degli scope).
 * <p>
 * Il client secret va passato come variabile d'ambiente
 * ({@code KEYCLOAK_CLIENT_SECRET}), mai hardcoded: questa classe vive in
 * src/main (non è un test JUnit, è uno strumento diagnostico standalone con
 * un proprio {@code main}), quindi finirebbe versionata come tutto il resto.
 */
public class TestOAuth2Connection {

    public static void main(String[] args) throws Exception {

        String tokenUri = "http://localhost:8080/realms/agritech/protocol/openid-connect/token";
        String clientId = "consumer-dati";
        String clientSecret = System.getenv("KEYCLOAK_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) {
            System.err.println("Impostare la variabile d'ambiente KEYCLOAK_CLIENT_SECRET "
                    + "(client secret del client '" + clientId + "' su Keycloak) prima di eseguire questo test.");
            System.exit(1);
        }

        System.out.println("1. Creazione del provider OAuth2...");
        CredentialsProvider provider = new OAuth2ClientCredentialsGrantCredentialsProvider
                .OAuth2ClientCredentialsGrantCredentialsProviderBuilder()
                .tokenEndpointUri(tokenUri)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("client_credentials")
                .parameter("scope", "rabbitmq.read:*/*")
                .build();

        System.out.println("2. Password (token) lunghezza: " + provider.getPassword().length());

        System.out.println("3. Creazione del refresh service...");
        CredentialsRefreshService refreshService = new DefaultCredentialsRefreshService
                .DefaultCredentialsRefreshServiceBuilder()
                .refreshDelayStrategy(
                        DefaultCredentialsRefreshService.ratioRefreshDelayStrategy(0.8)
                )
                .build();

        System.out.println("4. Configurazione ConnectionFactory nativo...");
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setCredentialsProvider(provider);
        factory.setCredentialsRefreshService(refreshService);
        System.out.println("Username che verrà inviato: '" + factory.getUsername() + "'");
        System.out.println("5. Tentativo di connessione ad AMQP...");
        try (Connection connection = factory.newConnection()) {
            System.out.println(">>> CONNESSIONE RIUSCITA! <<<");
            System.out.println("    Il token OAuth2 funziona con RabbitMQ via AMQP.");
        } catch (Exception e) {
            System.out.println(">>> CONNESSIONE FALLITA <<<");
            System.out.println("    Causa: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
