package io.github.gallileee.lendloop;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// One Clock for the whole app, so tests can move time (e.g. past an invite's expiry).
@Configuration(proxyBeanMethods = false)
class TimeConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
