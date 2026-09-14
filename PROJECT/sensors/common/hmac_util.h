#ifndef HMAC_UTIL_H
#define HMAC_UTIL_H

#include <string>

/*
* Calcola la firma HMAC-SHA256 di un messaggio usando una chiave segreta.
* Restituisce la firma come stringa esadecimale.
 */
std::string computeHMAC(const std::string& message, const std::string& key);

#endif
