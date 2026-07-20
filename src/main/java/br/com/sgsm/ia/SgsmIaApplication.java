package br.com.sgsm.ia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SgsmIaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SgsmIaApplication.class, args);
    }
}
