package org.pomelo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class PomeloAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PomeloAiApplication.class, args);
    }
}
