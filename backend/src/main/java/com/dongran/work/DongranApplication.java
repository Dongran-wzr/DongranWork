package com.dongran.work;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DongranApplication {
    public static void main(String[] args) { SpringApplication.run(DongranApplication.class, args); }
}
