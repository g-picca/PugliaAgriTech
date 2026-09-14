package it.puglia.agritech.report_service.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.puglia.agritech.report_service.dtos.ReportQueryRequest;
import it.puglia.agritech.report_service.dtos.ReportQueryResponse;
import it.puglia.agritech.report_service.services.ReportRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Aggregazioni", description = "Query di aggregazione puntuali sui dati dei sensori (AVG/MIN/MAX/COUNT)")
public class ReportController {

    private final ReportRequestService reportRequestService;

    @Operation(
            summary = "Richiede un'aggregazione (AVG/MIN/MAX/COUNT) su un campo dei sensori",
            description = "Inoltrata a consumer-service via RabbitMQ (request-reply con reply-to "
                    + "dinamico, timeout 5s). Il periodo si specifica o con 'from'/'to' (ISO-8601), "
                    + "o con 'onlyUnprocessed=true' + 'cutoff' per le sole letture non ancora incluse "
                    + "in un report — la stessa modalità usata internamente dal report giornaliero."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Aggregazione calcolata con successo"),
            @ApiResponse(responseCode = "400", description = "Richiesta non valida (campo/aggregazione sconosciuti, range invertito, formato data errato)"),
            @ApiResponse(responseCode = "504", description = "consumer-service non ha risposto entro il timeout", content = @Content)
    })
    @PostMapping("/aggregation")
    public ResponseEntity<ReportQueryResponse> aggregate(@RequestBody ReportQueryRequest request) {
        ReportQueryResponse response = reportRequestService.requestAggregation(request);

        if (response == null) {
            // Nessuna risposta entro il timeout: il consumer-service non ha
            // risposto (down, sovraccarico, o la richiesta si è persa).
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
        if (response.error() != null) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
}
