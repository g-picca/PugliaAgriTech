#ifndef MQTT_CLIENT_H
#define MQTT_CLIENT_H

#include <string>

/*
 * Wrapper dellla libreria Paho MQTT C++.
 * Gestisce la connessione al broker e la pubblicazione di messaggi.
*/
class MqttClient {
public:
    MqttClient(
        const std::string &brokerAddress,
        const std::string &clientId,
        const std::string &username,
        const std::string &password
    );

    // Si connette al broker. Restituisce true se la connessione riesce.
    bool connect();

    // Pubblica un messaggio su un topic. Restituisce true in caso di successo.
    bool publish(const std::string& topic, const std::string& payload);

    // Chiude la connessione.
    void disconnect();

private:
    std::string brokerAddress_;
    std::string clientId_;
    std::string username_;
    std::string password_;
    void* client_;
};

#endif