package com.minimes;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiniMesApplication {

    public static void main(String[] args) {
        // DB의 LOCALTIMESTAMP가 공장 현지 시간 기준이 되도록 JVM(=JDBC 세션) 시간대 고정
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(MiniMesApplication.class, args);
    }
}
