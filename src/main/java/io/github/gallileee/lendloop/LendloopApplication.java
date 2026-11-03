package io.github.gallileee.lendloop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LendloopApplication {

	public static void main(String[] args) {
		SpringApplication.run(LendloopApplication.class, args);
	}

}
