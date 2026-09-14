package it.puglia.agritech.report_service.services;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Salva e ritrova i PDF generati su filesystem locale (cartella 'reports/',
 * esclusa da Git: sono artefatti generati a runtime, non sorgenti).
 * Sufficiente per un'unica istanza del servizio; non pensato per un
 * deployment multi-istanza (in quel caso servirebbe uno storage condiviso,
 * es. un bucket S3-compatibile o un volume di rete).
 */
@Service
public class ReportStorageService {

    private static final DateTimeFormatter FILENAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneOffset.UTC);

    private final Path baseDir;

    public ReportStorageService(@Value("${agritech.reports.storage-path}") String storagePath) {
        this.baseDir = Path.of(storagePath);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(baseDir);
    }

    /**
     * Il nome file inizia con la data/ora in formato ordinabile
     * lessicograficamente (yyyy-MM-dd_HHmmss): l'ordinamento alfabetico dei
     * nomi coincide con l'ordinamento cronologico, comodo per trovare
     * "l'ultimo generato" senza dover leggere i metadati del file.
     */
    public Path save(Instant generatedAt, byte[] pdfBytes) throws IOException {
        String filename = "report-" + FILENAME_FORMAT.format(generatedAt) + ".pdf";
        Path path = baseDir.resolve(filename);
        Files.write(path, pdfBytes);
        return path;
    }

    public Optional<Path> findLatest() throws IOException {
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        }
    }

    /**
     * @param date formato "yyyy-MM-dd"; se generato più volte nello stesso
     *             giorno, restituisce il più recente di quel giorno.
     */
    public Optional<Path> findByDate(String date) throws IOException {
        String prefix = "report-" + date;
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        }
    }
}
