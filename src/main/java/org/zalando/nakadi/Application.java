package org.zalando.nakadi;

import com.unitedtraders.metrics.prometheus.EnablePrometheusExporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnablePrometheusExporter
@SpringBootApplication
public class Application {

    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
