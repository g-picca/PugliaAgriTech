#include "hmac_util.h"
#include "mqtt_client.h"

#include <iostream>
#include <string>
#include <random>
#include <thread>
#include <chrono>
#include <cstdlib>
#include <ctime>
#include <sstream>
#include <iomanip>

// Legge una variabile d'ambiente, con un valore di default se non presente.
std::string getEnv(const std::string& name, const std::string& defaultValue) {
    const char* value = std::getenv(name.c_str());
    return value ? std::string(value) : defaultValue;
}

// Restituisce il timestamp corrente in formato ISO 8601 UTC.
std::string currentTimestamp() {
    auto now = std::chrono::system_clock::now();
    std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
    gmtime_r(&t, &tm);
    std::ostringstream oss;
    oss << std::put_time(&tm, "%Y-%m-%dT%H:%M:%SZ");
    return oss.str();
}

int main() {
    // Configurazione letta dalle variabili d'ambiente
    const std::string broker   = getEnv("MQTT_BROKER", "tcp://rabbitmq:1883");
    const std::string deviceId = getEnv("DEVICE_ID", "AGRO_STATION_SUD");
    const std::string mqttUser = getEnv("MQTT_USER", "mqtt_sensor_user");
    const std::string mqttPass = getEnv("MQTT_PASS", "mqtt_sensor_password");
    const std::string hmacKey  = getEnv("HMAC_KEY", "chiave_di_default");
    const std::string topic    = "agritech/sensors/agrometeo";

    const int intervalSeconds = std::stoi(getEnv("INTERVAL_SECONDS", "5"));

    // Generatore di numeri casuali per simulare le letture
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_real_distribution<> soilMoist(15.0, 35.0); // umidità suolo
    std::uniform_real_distribution<> airTemp(18.0, 34.0); // temperatura aria
    std::uniform_real_distribution<> airHum(45.0, 75.0); // umidità aria
    std::uniform_int_distribution<>  leafWet(0, 6); // ore bagnatura fogliare

    // Connessione al broker MQTT
    MqttClient client(broker, deviceId, mqttUser, mqttPass);
    if (!client.connect()) {
        std::cerr << "Impossibile connettersi al broker. Uscita." << std::endl;
        return 1;
    }

    std::cout << "Sensore Agrometeo '" << deviceId << "' avviato." << std::endl;

    // Ciclo principale: genera e pubblica i dati
    while (true) {
        std::ostringstream dataStream;
        dataStream << std::fixed << std::setprecision(2)
                   << "{"
                   << "\"soil_moisture_percent\":" << soilMoist(gen) << ","
                   << "\"air_temperature_c\":" << airTemp(gen) << ","
                   << "\"air_humidity_percent\":" << airHum(gen) << ","
                   << "\"leaf_wetness_hours\":" << leafWet(gen)
                   << "}";
        std::string data = dataStream.str();
        std::string timestamp = currentTimestamp();

        std::ostringstream payloadNoSig;
        payloadNoSig << "{"
                     << "\"device_id\":\"" << deviceId << "\","
                     << "\"sensor_type\":\"AGROMETEO\","
                     << "\"timestamp\":\"" << timestamp << "\","
                     << "\"data\":" << data
                     << "}";

        std::string signature = computeHMAC(payloadNoSig.str(), hmacKey);

        // Il payload finale riusa esattamente la stringa firmata (payloadNoSig),
        // aggiungendo solo il campo signature: firmato e trasmesso devono
        // coincidere byte per byte, altrimenti la verifica lato consumer fallisce.
        std::string finalPayload = payloadNoSig.str();
        finalPayload.pop_back(); // rimuove la '}' finale
        finalPayload += ",\"signature\":\"" + signature + "\"}";

        client.publish(topic, finalPayload);

        std::this_thread::sleep_for(std::chrono::seconds(intervalSeconds));
    }

    client.disconnect();
    return 0;
}