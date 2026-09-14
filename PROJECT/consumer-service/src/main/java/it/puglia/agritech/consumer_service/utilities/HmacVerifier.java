package it.puglia.agritech.consumer_service.utilities;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica l'integrità e l'autenticità dei messaggi dei sensori tramite
 * HMAC-SHA256, con la stessa chiave condivisa usata dal firmatario C++
 * (sensors/common/hmac_util.cpp).
 * <p>
 * La firma è calcolata dal sensore sull'intero payload JSON TRANNE il
 * campo "signature" stesso: includere la firma nel proprio calcolo non è
 * possibile per definizione (il suo valore dipende dal risultato che si
 * starebbe calcolando). Per questo la verifica qui non ri-serializza il
 * JSON con una libreria (rischio di formattazione diversa da quella
 * prodotta in C++, es. precisione decimale), ma opera sui byte grezzi del
 * messaggio così come arrivati sul wire, isolando la porzione firmata con
 * una corrispondenza di stringa esatta.
 */
@Component
public class HmacVerifier {

    /**
     * Il payload dei sensori termina sempre con ,"signature":"<64 esadecimali>"}
     * (HMAC-SHA256 produce 32 byte, cioè 64 caratteri esadecimali).
     */
    private static final Pattern SIGNED_PAYLOAD = Pattern.compile("^(.*),\"signature\":\"([0-9a-fA-F]{64})\"}\\s*$", Pattern.DOTALL);
    private final SecretKeySpec keySpec;

    public HmacVerifier(@Value("${agritech.hmac.secret-key}") String secretKey) {
        this.keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public record Result(boolean valid, String payloadWithoutSignature) {
        static Result invalid() {
            return new Result(false, null);
        }
    }

    /**
     * Verifica la firma di un messaggio grezzo. Non lancia eccezioni per
     * payload malformati: un formato inatteso è semplicemente "non valido",
     * al pari di una firma sbagliata (stesso trattamento: scarto del
     * messaggio, non un errore di sistema).
     */
    public Result verify(byte[] rawMessage) {
        String raw = new String(rawMessage, StandardCharsets.UTF_8);
        Matcher matcher = SIGNED_PAYLOAD.matcher(raw);
        if (!matcher.matches()) {
            return Result.invalid();
        }

        String payloadWithoutSignature = matcher.group(1) + "}";
        String providedSignatureHex = matcher.group(2);

        byte[] expected = computeHmac(payloadWithoutSignature.getBytes(StandardCharsets.UTF_8));
        byte[] provided = hexToBytes(providedSignatureHex);

        // Confronto in tempo costante: evita che un attaccante possa dedurre
        // byte per byte la firma corretta misurando i tempi di risposta
        // (timing attack), cosa che String.equals()/Arrays.equals() non garantiscono.
        boolean valid = MessageDigest.isEqual(expected, provided);
        return new Result(valid, valid ? payloadWithoutSignature : null);
    }

    private byte[] computeHmac(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Impossibile calcolare HMAC-SHA256", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
