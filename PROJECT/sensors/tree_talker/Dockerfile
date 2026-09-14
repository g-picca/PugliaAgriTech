# STAGE 1 — Builder: compila il sensore
FROM ubuntu:24.04 AS builder

# Installa i tool di compilazione e le dipendenze
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Compila e installa la libreria Paho MQTT C
RUN git clone https://github.com/eclipse/paho.mqtt.c.git /tmp/paho-c \
    && cd /tmp/paho-c \
    && cmake -Bbuild -H. -DPAHO_WITH_SSL=ON -DPAHO_ENABLE_TESTING=OFF \
    && cmake --build build/ --target install

# Compila e installa la libreria Paho MQTT C++
RUN git clone https://github.com/eclipse/paho.mqtt.cpp.git /tmp/paho-cpp \
    && cd /tmp/paho-cpp \
    && cmake -Bbuild -H. -DPAHO_BUILD_EXAMPLES=OFF -DPAHO_ENABLE_TESTING=OFF \
    && cmake --build build/ --target install \
    && ldconfig

# Copia il codice sorgente
COPY common /common
COPY tree_talker /app
WORKDIR /app

# Compila il sensore
RUN cmake -Bbuild -H. && cmake --build build/

# STAGE 2 — Runtime: immagine leggera finale
FROM ubuntu:24.04

# Installa solo le librerie necessarie all'esecuzione (non i tool di build)
RUN apt-get update && apt-get install -y \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Copia le librerie Paho compilate nello stage precedente
COPY --from=builder /usr/local/lib/libpaho* /usr/local/lib/
RUN ldconfig

# Copia l'eseguibile compilato
COPY --from=builder /app/build/tree_talker /usr/local/bin/tree_talker

# Comando di avvio del container
CMD ["tree_talker"]