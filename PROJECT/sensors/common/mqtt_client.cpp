#include "mqtt_client.h"
#include <mqtt/client.h>
#include <iostream>

MqttClient::MqttClient(
    const std::string &brokerAddress,
    const std::string &clientId,
    const std::string &username,
    const std::string &password
)
    : brokerAddress_(brokerAddress),
      clientId_(clientId),
      username_(username),
      password_(password),
      client_(nullptr) {
    client_ = new mqtt::client(brokerAddress_, clientId_); // Crea l'oggetto client Paho
}

bool MqttClient::connect() {
    auto *cli = static_cast<mqtt::client *>(client_);
    mqtt::connect_options connOpts;
    connOpts.set_user_name(username_);
    connOpts.set_password(password_);
    connOpts.set_keep_alive_interval(20);
    connOpts.set_clean_session(true);

    try {
        cli->connect(connOpts);
        std::cout << "[MQTT] Connesso al broker " << brokerAddress_
                << " come " << clientId_ << std::endl;
        return true;
    } catch (const mqtt::exception &e) {
        std::cerr << "[MQTT] Errore di connessione: " << e.what() << std::endl;
        return false;
    }
}

bool MqttClient::publish(const std::string &topic, const std::string &payload) {
    auto *cli = static_cast<mqtt::client *>(client_);
    try {
        auto msg = mqtt::make_message(topic, payload);
        msg->set_qos(1); // QoS 1: consegna garantita almeno una volta
        cli->publish(msg);
        std::cout << "[MQTT] Pubblicato su '" << topic << "': "
                << payload << std::endl;
        return true;
    } catch (const mqtt::exception &e) {
        std::cerr << "[MQTT] Errore di pubblicazione: " << e.what() << std::endl;
        return false;
    }
}

void MqttClient::disconnect() {
    auto *cli = static_cast<mqtt::client *>(client_);
    try {
        cli->disconnect();
        std::cout << "[MQTT] Disconnesso." << std::endl;
    } catch (const mqtt::exception &e) {
        std::cerr << "[MQTT] Errore di disconnessione: " << e.what() << std::endl;
    }
    delete cli;
    client_ = nullptr;
}
