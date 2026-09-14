package it.puglia.agritech.report_service.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.puglia.agritech.report_service.services.DailyReportService;
import it.puglia.agritech.report_service.services.ReportStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@RestController
@RequestMapping("/api/reports/daily")
@RequiredArgsConstructor
@Tag(name = "Report giornaliero", description = "Generazione e download del report PDF")
public class DailyReportController {

    private final DailyReportService dailyReportService;
    private final ReportStorageService reportStorageService;

    @Operation(
            summary = "Genera il report del giorno on-demand",
            description = "Oltre alla generazione schedulata (ogni notte alle 00:05), utile per "
                    + "test e demo senza dover aspettare lo scheduler. Aggrega solo le letture "
                    + "sensori non ancora incluse in un report precedente (campo 'processed'); "
                    + "al termine le marca come processate, ma solo se il PDF è stato salvato con "
                    + "successo."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report generato e salvato"),
            @ApiResponse(responseCode = "409", description = "Una generazione è già in corso (lock interno)", content = @Content),
            @ApiResponse(responseCode = "500", description = "Errore durante la generazione (es. consumer-service non raggiungibile)", content = @Content)
    })
    @PostMapping("/generate")
    public ResponseEntity<String> generate() {
        DailyReportService.GenerationResult result = dailyReportService.generateDailyReport();

        if (result.alreadyRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Generazione già in corso, riprova tra poco");
        }
        if (!result.success()) {
            return ResponseEntity.internalServerError().body("Errore nella generazione: " + result.error());
        }
        return ResponseEntity.ok("Report generato: " + result.path().getFileName());
    }

    @Operation(summary = "Scarica l'ultimo report generato")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF trovato",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "Nessun report ancora generato", content = @Content)
    })
    @GetMapping("/latest")
    public ResponseEntity<byte[]> latest() throws IOException {
        Optional<Path> path = reportStorageService.findLatest();
        return path.isPresent() ? servePdf(path.get()) : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Scarica il report di una data specifica",
            description = "Se in quella data sono stati generati più report, restituisce il più recente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF trovato",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "Nessun report per la data indicata", content = @Content)
    })
    @GetMapping("/{date}")
    public ResponseEntity<byte[]> byDate(
            @Parameter(description = "Data nel formato yyyy-MM-dd", example = "2026-09-14")
            @PathVariable String date
    ) throws IOException {
        Optional<Path> path = reportStorageService.findByDate(date);
        return path.isPresent() ? servePdf(path.get()) : ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> servePdf(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(bytes);
    }
}
