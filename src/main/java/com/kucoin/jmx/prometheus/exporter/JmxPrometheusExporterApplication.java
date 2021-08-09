package com.kucoin.jmx.prometheus.exporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JmxPrometheusExporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(JmxPrometheusExporterApplication.class, args);
    }

}
