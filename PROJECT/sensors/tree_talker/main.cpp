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
    const std::string deviceId = getEnv("DEVICE_ID", "OLIVO_SEC_001");
    const std::string mqttUser = getEnv("MQTT_USER", "mqtt_sensor_user");
    const std::string mqttPass = getEnv("MQTT_PASS", "mqtt_sensor_password");
    const std::string hmacKey  = getEnv("HMAC_KEY", "chiave_di_default");
    const std::string topic    = "agritech/sensors/treetalker";

    // Intervallo tra un invio e l'altro (secondi)
    const int intervalSeconds = std::stoi(getEnv("INTERVAL_SECONDS", "60"));

    // Generatore di numeri casuali per simulare le letture
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_real_distribution<> sapFlow(8.0, 20.0); // flusso linfatico normale
    std::uniform_real_distribution<> stemHum(40.0, 55.0); // umidità del fusto
    std::uniform_real_distribution<> leafIdx(0.70, 1.0); // vigore fogliare

    // Connessione al broker MQTT
    MqttClient client(broker, deviceId, mqttUser, mqttPass);
    if (!client.connect()) {
        std::cerr << "Impossibile connettersi al broker. Uscita." << std::endl;
        return 1;
    }

    std::cout << "Sensore TreeTalker '" << deviceId << "' avviato." << std::endl;

    // Ciclo principale: genera e pubblica i dati
    while (true) {
        // Costruisce il blocco "data" con le letture simulate
        std::ostringstream dataStream;
        dataStream << std::fixed << std::setprecision(2)
                   << "{"
                   << "\"sap_flow_cm_hr\":" << sapFlow(gen) << ","
                   << "\"stem_humidity_percent\":" << stemHum(gen) << ","
                   << "\"leaf_color_index\":" << leafIdx(gen)
                   << "}";
        std::string data = dataStream.str();
        std::string timestamp = currentTimestamp();

        // Costruisce il payload senza firma, per calcolare l'HMAC
        std::ostringstream payloadNoSig;
        payloadNoSig << "{"
                     << "\"device_id\":\"" << deviceId << "\","
                     << "\"sensor_type\":\"TREE_TALKER\","
                     << "\"timestamp\":\"" << timestamp << "\","
                     << "\"data\":" << data
                     << "}";

        // Calcola la firma HMAC del payload
        std::string signature = computeHMAC(payloadNoSig.str(), hmacKey);

        // Il payload finale riusa esattamente la stringa firmata (payloadNoSig),
        // aggiungendo solo il campo signature: firmato e trasmesso devono
        // coincidere byte per byte, altrimenti la verifica lato consumer fallisce.
        std::string finalPayload = payloadNoSig.str();
        finalPayload.pop_back(); // rimuove la '}' finale
        finalPayload += ",\"signature\":\"" + signature + "\"}";

        // Pubblica sul broker
        client.publish(topic, finalPayload);

        // Attende prima del prossimo invio
        std::this_thread::sleep_for(std::chrono::seconds(intervalSeconds));
    }

    client.disconnect();
    return 0;
}