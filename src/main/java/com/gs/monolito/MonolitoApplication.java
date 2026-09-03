package com.gs.monolito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class MonolitoApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonolitoApplication.class, args);
	}

	/**
	 * Executor acotado para los métodos @Async (hoy: NotificacionBotService,
	 * para que la notificación de WhatsApp no bloquee el cambio de estado del
	 * pedido). El default de Spring (SimpleAsyncTaskExecutor) crea un thread
	 * nuevo sin límite por cada llamada — inofensivo con poco volumen, pero
	 * acá todo corre en un solo proceso pensado para 1-2GB de RAM, así que
	 * conviene acotarlo.
	 */
	@Bean
	public Executor taskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("gs-async-");
		executor.initialize();
		return executor;
	}

}
