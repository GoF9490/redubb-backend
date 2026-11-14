package com.streaming.music;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.streaming.music.global.properties")
public class MusicApplication {

	public static void main(String[] args) {
		SpringApplication.run(MusicApplication.class, args);
	}

}
