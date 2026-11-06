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

// Counts attempts per email address and per IP address in a sliding window, and says how long to
// wait once either reaches its limit. Used for failed logins (LoginThrottle) and for password
// reset requests. Callers decide what counts as an attempt; refused attempts shouldn't be
// recorded, so a pause always ends after the window.
//
// The email is counted whether or not an account exists, so the answer doesn't tell which do.
// In memory: it starts empty after a restart and assumes one app instance.
public class AttemptLimiter {

	private static final Logger log = LoggerFactory.getLogger(AttemptLimiter.class);

	// An upper bound on remembered keys, so a flood of made-up emails can't use up the memory.
	static final int MAX_KEYS = 100_000;
	private static final int MAX_EMAIL_KEY_LENGTH = 320;

	private final int maxPerEmail;
	private final int maxPerIp;
	private final Duration window;
	private final Clock clock;
	private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

	public AttemptLimiter(int maxPerEmail, int maxPerIp, Duration window, Clock clock) {
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

	public void record(String email, String ip) {
		Instant now = clock.instant();
		if (failures.size() >= MAX_KEYS) {
			purgeExpired();
		}
		emailKey(email).ifPresent(key -> add(key, maxPerEmail, now));
		ipKey(ip).ifPresent(key -> add(key, maxPerIp, now));
	}

	// Forgets the email's attempts; the IP's stay.
	public void forget(String email) {
		emailKey(email).ifPresent(failures::remove);
	}

	// Forget windows that have passed, so the map only holds recent failures.
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
			log.warn("Attempt limiter is full ({} keys); not tracking {}", MAX_KEYS, key.startsWith("ip:") ? key : "an email");
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
