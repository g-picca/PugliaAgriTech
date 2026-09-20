package it.puglia.agritech.report_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(info = @Info(
        title = "PugliAgriTech report-service",
        description = "Generazione e download del report PDF giornaliero sui dati dei sensori "
                + "(TreeTalker/Agrometeo), e query di aggregazione su richiesta. Non persiste "
                + "nulla direttamente: recupera i dati da consumer-service via RabbitMQ "
                + "(request-reply con reply-to dinamico), mai un accesso diretto al database.",
        version = "v1"
))
public class ReportServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportServiceApplication.class, args);
    }
}
