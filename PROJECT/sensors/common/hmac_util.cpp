#include "hmac_util.h"
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <sstream>
#include <iomanip>

std::string computeHMAC(const std::string& message, const std::string& key) {
    unsigned char hash[EVP_MAX_MD_SIZE];
    unsigned int hashLen = 0;

    HMAC(
        EVP_sha256(),
        key.data(), static_cast<int>(key.size()),
        reinterpret_cast<const unsigned char*>(message.data()), message.size(),
        hash, &hashLen
    );

    std::ostringstream oss;
    for (unsigned int i = 0; i < hashLen; i++) {
        oss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(hash[i]);
    }
    return oss.str();
}