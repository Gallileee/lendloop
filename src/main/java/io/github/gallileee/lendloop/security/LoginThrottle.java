package io.github.gallileee.lendloop.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Slows down password guessing by counting failed logins:
//   - per email address: few failures, so one account can't be guessed at (from any number of IPs);
//   - per IP address: more, so one machine can't try a few passwords on many accounts.
// While a limit is reached, every login for that email / from that IP is refused without checking
// the password (LoginThrottleFilter), so a guess that happens to be right isn't revealed either.
@Component
public class LoginThrottle extends AttemptLimiter {

	public LoginThrottle(@Value("${lendloop.login.max-failures-per-email:5}") int maxPerEmail,
			@Value("${lendloop.login.max-failures-per-ip:30}") int maxPerIp,
			@Value("${lendloop.login.window:15m}") Duration window, Clock clock) {
		super(maxPerEmail, maxPerIp, window, clock);
	}

	public void recordFailure(String email, String ip) {
		record(email, ip);
	}

	// A successful login clears that email's failures (typos earlier don't count against later
	// ones). The IP's count stays: otherwise logging into one's own account between guesses at
	// other accounts would reset it.
	public void recordSuccess(String email) {
		forget(email);
	}

	// Forget windows that have passed, so memory only holds recent failures.
	@Scheduled(fixedDelayString = "${lendloop.login.purge-interval:10m}")
	public void purge() {
		purgeExpired();
	}
}
