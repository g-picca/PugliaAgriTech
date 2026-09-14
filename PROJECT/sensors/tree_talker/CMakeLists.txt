cmake_minimum_required(VERSION 3.16)
project(tree_talker_sensor LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Eseguibile: unisce il main del sensore con il codice comune
add_executable(tree_talker
    main.cpp
    /common/hmac_util.cpp
    /common/mqtt_client.cpp
)

# Percorso degli header comuni
target_include_directories(tree_talker PRIVATE /common)

# Collega le librerie: Paho MQTT C++ e OpenSSL (per l'HMAC)
target_link_libraries(tree_talker
    PRIVATE
    paho-mqttpp3
    paho-mqtt3c
    crypto
)