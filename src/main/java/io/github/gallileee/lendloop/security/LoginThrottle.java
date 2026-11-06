package io.github.gallileee.lendloop.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Slows down password guessing by counting failed logins in a sliding window:
//   - per email address: few failures, so one account can't be guessed at (from any number of IPs);
//   - per IP address: more, so one machine can't try a few passwords on many accounts.
// While a limit is reached, every login for that email / from that IP is refused without checking
// the password, so a guess that happens to be right isn't revealed either.
//
// The email is counted whether or not an account exists, so the answer doesn't tell which do.
// Only failures count; refused attempts while blocked don't, so a lockout always ends after the
// window. In memory: it starts empty after a restart and assumes one app instance.
@Component
public class LoginThrottle {

	private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

	// An upper bound on remembered keys, so a flood of made-up emails can't use up the memory.
	static final int MAX_KEYS = 100_000;
	private static final int MAX_EMAIL_KEY_LENGTH = 320;

	private final int maxPerEmail;
	private final int maxPerIp;
	private final Duration window;
	private final Clock clock;
	private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

	public LoginThrottle(@Value("${lendloop.login.max-failures-per-email:5}") int maxPerEmail,
			@Value("${lendloop.login.max-failures-per-ip:30}") int maxPerIp,
			@Value("${lendloop.login.window:15m}") Duration window, Clock clock) {
		this.maxPerEmail = maxPerEmail;
		this.maxPerIp = maxPerIp;
		this.window = window;
		this.clock = clock;
	}

	public Duration window() {
		return window;
	}

	// How long until a login for this email from this IP is allowed again; empty if it is now.
	public Optional<Duration> blockedFor(String email, String ip) {
		Instant now = clock.instant();
		Optional<Duration> byEmail = emailKey(email).flatMap(key -> waitFor(key, maxPerEmail, now));
		Optional<Duration> byIp = ipKey(ip).flatMap(key -> waitFor(key, maxPerIp, now));
		if (byEmail.isPresent() && byIp.isPresent()) {
			return Optional.of(byEmail.get().compareTo(byIp.get()) >= 0 ? byEmail.get() : byIp.get());
		}
		return byEmail.or(() -> byIp);
	}

	public void recordFailure(String email, String ip) {
		Instant now = clock.instant();
		if (failures.size() >= MAX_KEYS) {
			purgeExpired();
		}
		emailKey(email).ifPresent(key -> add(key, maxPerEmail, now));
		ipKey(ip).ifPresent(key -> add(key, maxPerIp, now));
	}

	// A successful login clears that email's failures (typos earlier don't count against later
	// ones). The IP's count stays: otherwise logging into one's own account between guesses at
	// other accounts would reset it.
	public void recordSuccess(String email) {
		emailKey(email).ifPresent(failures::remove);
	}

	// Forget windows that have passed, so the map only holds recent failures.
	@Scheduled(fixedDelayString = "${lendloop.login.purge-interval:10m}")
	public void purgeExpired() {
		Instant cutoff = clock.instant().minus(window);
		failures.entrySet().removeIf(entry -> {
			synchronized (entry.getValue()) {
				return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff);
			}
		});
	}

	// For tests: all requests in MockMvc come from the same IP.
	public void clear() {
		failures.clear();
	}

	int trackedKeys() {
		return failures.size();
	}

	private void add(String key, int max, Instant now) {
		if (!failures.containsKey(key) && failures.size() >= MAX_KEYS) {
			log.warn("Login throttle is full ({} keys); not tracking {}", MAX_KEYS, key.startsWith("ip:") ? key : "an email");
			return;
		}
		Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (times) {
			times.addLast(now);
			// Only the last `max` failures can decide whether the limit is reached.
			while (times.size() > max) {
				times.removeFirst();
			}
		}
	}

	// Blocked while the oldest of the last `max` failures is still inside the window.
	private Optional<Duration> waitFor(String key, int max, Instant now) {
		Deque<Instant> times = failures.get(key);
		if (times == null) {
			return Optional.empty();
		}
		synchronized (times) {
			if (times.size() < max) {
				return Optional.empty();
			}
			Instant until = times.peekFirst().plus(window);
			return until.isAfter(now) ? Optional.of(Duration.between(now, until)) : Optional.empty();
		}
	}

	// Emails are matched like at login: ignoring case and surrounding spaces.
	private static Optional<String> emailKey(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		String normalised = email.strip().toLowerCase(Locale.ROOT);
		if (normalised.length() > MAX_EMAIL_KEY_LENGTH) {
			normalised = normalised.substring(0, MAX_EMAIL_KEY_LENGTH);
		}
		return Optional.of("email:" + normalised);
	}

	private static Optional<String> ipKey(String ip) {
		return ip == null || ip.isBlank() ? Optional.empty() : Optional.of("ip:" + ip);
	}
}
