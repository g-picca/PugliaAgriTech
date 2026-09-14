package it.puglia.agritech.report_service.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test di ReportStorageService (sezione 10.2 del riepilogo) su una cartella
 * temporanea di JUnit, non su 'reports/': verifica salvataggio, ricerca
 * dell'ultimo generato e ricerca per data, senza toccare file reali del
 * progetto ne' richiedere configurazione esterna.
 */
class ReportStorageServiceTest {

    @TempDir
    Path tempDir;

    private ReportStorageService newService() throws Exception {
        ReportStorageService service = new ReportStorageService(tempDir.toString());
        service.init(); // normalmente invocato da Spring via @PostConstruct
        return service;
    }

    @Test
    void save_thenFindLatest_returnsTheSavedFile() throws Exception {
        ReportStorageService service = newService();

        Path saved = service.save(Instant.parse("2026-01-01T10:00:00Z"), "contenuto".getBytes(StandardCharsets.UTF_8));

        assertThat(service.findLatest()).contains(saved);
    }

    @Test
    void findLatest_multipleReports_picksTheMostRecentByGenerationTime() throws Exception {
        ReportStorageService service = newService();

        service.save(Instant.parse("2026-01-01T10:00:00Z"), "a".getBytes(StandardCharsets.UTF_8));
        Path second = service.save(Instant.parse("2026-01-02T10:00:00Z"), "b".getBytes(StandardCharsets.UTF_8));

        assertThat(service.findLatest()).contains(second);
    }

    @Test
    void findLatest_emptyDirectory_returnsEmpty() throws Exception {
        ReportStorageService service = newService();

        assertThat(service.findLatest()).isEmpty();
    }

    @Test
    void findByDate_matchesOnlyThatDaysReport() throws Exception {
        ReportStorageService service = newService();

        Path day1 = service.save(Instant.parse("2026-01-01T10:00:00Z"), "a".getBytes(StandardCharsets.UTF_8));
        service.save(Instant.parse("2026-01-02T10:00:00Z"), "b".getBytes(StandardCharsets.UTF_8));

        Optional<Path> found = service.findByDate("2026-01-01");
        assertThat(found).contains(day1);
    }

    @Test
    void findByDate_multipleReportsSameDay_picksTheMostRecent() throws Exception {
        ReportStorageService service = newService();

        service.save(Instant.parse("2026-01-01T08:00:00Z"), "a".getBytes(StandardCharsets.UTF_8));
        Path later = service.save(Instant.parse("2026-01-01T20:00:00Z"), "b".getBytes(StandardCharsets.UTF_8));

        assertThat(service.findByDate("2026-01-01")).contains(later);
    }

    @Test
    void findByDate_noMatch_returnsEmpty() throws Exception {
        ReportStorageService service = newService();

        service.save(Instant.parse("2026-01-01T10:00:00Z"), "a".getBytes(StandardCharsets.UTF_8));

        assertThat(service.findByDate("2099-12-31")).isEmpty();
    }
}
