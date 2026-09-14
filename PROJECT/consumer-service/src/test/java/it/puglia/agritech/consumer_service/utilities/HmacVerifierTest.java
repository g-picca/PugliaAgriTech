package it.puglia.agritech.consumer_service.utilities;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test della verifica HMAC-SHA256 (sezione 8.2 del riepilogo): nessun
 * contesto Spring necessario, HmacVerifier è istanziabile direttamente con
 * una chiave di test — @Value serve solo quando è Spring a costruirlo.
 */
class HmacVerifierTest {

    private static final String SECRET = "chiave-di-test-hmac";

    private final HmacVerifier verifier = new HmacVerifier(SECRET);

    @Test
    void verify_validSignature_isAcceptedAndPayloadIsExtracted() {
        String payloadNoSig = "{\"device_id\":\"D1\",\"sensor_type\":\"TREE_TALKER\",\"data\":{\"x\":1}}";
        String signed = sign(payloadNoSig, SECRET);

        HmacVerifier.Result result = verifier.verify(signed.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isTrue();
        assertThat(result.payloadWithoutSignature()).isEqualTo(payloadNoSig);
    }

    @Test
    void verify_payloadTamperedAfterSigning_isRejected() {
        String payloadNoSig = "{\"device_id\":\"D1\",\"data\":{\"value\":1}}";
        String signed = sign(payloadNoSig, SECRET);
        // Il contenuto viene alterato dopo il calcolo della firma: la firma
        // originale non corrisponde più al nuovo contenuto.
        String tampered = signed.replace("\"value\":1", "\"value\":2");

        HmacVerifier.Result result = verifier.verify(tampered.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
        assertThat(result.payloadWithoutSignature()).isNull();
    }

    @Test
    void verify_signatureReplacedWithGarbage_isRejected() {
        String payloadNoSig = "{\"device_id\":\"D1\"}";
        String signed = sign(payloadNoSig, SECRET);
        String withBadSignature = signed.replaceAll("\"signature\":\"[0-9a-fA-F]{64}\"",
                "\"signature\":\"" + "0".repeat(64) + "\"");

        HmacVerifier.Result result = verifier.verify(withBadSignature.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void verify_signedWithDifferentKey_isRejected() {
        // Simula un mittente che non conosce la chiave condivisa reale.
        String payloadNoSig = "{\"device_id\":\"D1\"}";
        String signedWithOtherKey = sign(payloadNoSig, "chiave-diversa");

        HmacVerifier.Result result = verifier.verify(signedWithOtherKey.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void verify_missingSignatureField_isRejected() {
        String noSignatureAtAll = "{\"device_id\":\"D1\",\"data\":{\"x\":1}}";

        HmacVerifier.Result result = verifier.verify(noSignatureAtAll.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void verify_signatureWithWrongLength_isRejected() {
        // 6 caratteri esadecimali invece dei 64 attesi per HMAC-SHA256: non
        // supera nemmeno il controllo di formato, prima ancora di calcolare
        // l'HMAC per il confronto.
        String withShortSignature = "{\"device_id\":\"D1\",\"signature\":\"abc123\"}";

        HmacVerifier.Result result = verifier.verify(withShortSignature.getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void verify_emptyMessage_isRejectedWithoutThrowing() {
        HmacVerifier.Result result = verifier.verify(new byte[0]);

        assertThat(result.valid()).isFalse();
    }

    private static String sign(String payloadNoSig, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payloadNoSig.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return payloadNoSig.substring(0, payloadNoSig.length() - 1)
                    + ",\"signature\":\"" + hex + "\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
