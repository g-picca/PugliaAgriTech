package it.puglia.agritech.report_service.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DailyReportScheduler {

    private final DailyReportService dailyReportService;

    /**
     * Ogni giorno alle 00:05 (orario del server). L'orario è deliberatamente
     * a inizio giornata, non a mezzanotte esatta, per lasciare un margine a
     * eventuali letture ancora in transito nella coda sensor.data.
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void scheduledGeneration() {
        log.info("Avvio generazione schedulata del report giornaliero");
        dailyReportService.generateDailyReport();
    }
}
