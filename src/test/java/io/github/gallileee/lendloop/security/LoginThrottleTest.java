package io.github.gallileee.lendloop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

// The sliding-window rules on their own, with a clock the test moves.
class LoginThrottleTest {

	private static final String IP = "203.0.113.7";

	private final MovableClock clock = new MovableClock(Instant.parse("2026-11-06T12:00:00Z"));
	// 3 per email, 5 per IP, 10 minutes: small numbers keep the tests short.
	private final LoginThrottle throttle = new LoginThrottle(3, 5, Duration.ofMinutes(10), clock);

	private void fail(String email, String ip, int times) {
		for (int i = 0; i < times; i++) {
			throttle.recordFailure(email, ip);
		}
	}

	@Test
	void anEmailIsBlockedAfterTheLimitUntilTheOldestFailureLeavesTheWindow() {
		fail("sam@example.com", IP, 2);
		assertThat(throttle.blockedFor("sam@example.com", IP)).isEmpty();

		clock.advance(Duration.ofMinutes(4));
		fail("sam@example.com", IP, 1);
		// The first failure was 4 minutes ago, so 6 minutes remain.
		assertThat(throttle.blockedFor("sam@example.com", IP)).contains(Duration.ofMinutes(6));
		assertThat(throttle.blockedFor("sam@example.com", "198.51.100.1")).as("from any IP").isPresent();

		clock.advance(Duration.ofMinutes(6));
		assertThat(throttle.blockedFor("sam@example.com", IP)).isEmpty();
	}

	@Test
	void emailsAreMatchedIgnoringCaseAndSpaces() {
		fail(" Sam@Example.COM ", IP, 3);

		assertThat(throttle.blockedFor("sam@example.com", "198.51.100.1")).isPresent();
	}

	@Test
	void oneIpTryingManyAccountsIsBlockedForAllOfThem() {
		for (int i = 0; i < 5; i++) {
			throttle.recordFailure("user" + i + "@example.com", IP);
		}

		assertThat(throttle.blockedFor("someone-new@example.com", IP)).isPresent();
		assertThat(throttle.blockedFor("someone-new@example.com", "198.51.100.1")).as("other IPs").isEmpty();
	}

	@Test
	void aSuccessClearsTheEmailButNotTheIp() {
		fail("sam@example.com", IP, 2);
		throttle.recordSuccess("SAM@example.com");
		fail("sam@example.com", IP, 2);
		assertThat(throttle.blockedFor("sam@example.com", IP)).as("only 2 since the success").isEmpty();

		// The IP has seen 4 failures; logging in somewhere didn't reset that.
		throttle.recordFailure("kim@example.com", IP);
		assertThat(throttle.blockedFor("anyone@example.com", IP)).isPresent();
	}

	@Test
	void withBothLimitsReachedTheLongerWaitIsGiven() {
		fail("sam@example.com", IP, 2);
		clock.advance(Duration.ofMinutes(5));
		fail("kim@example.com", IP, 3);
		clock.advance(Duration.ofMinutes(1));
		fail("sam@example.com", IP, 1);
		throttle.recordFailure("lee@example.com", IP);

		// Sam's email: its 3 failures started 6 minutes ago -> 4 minutes left.
		// The IP: its last 5 failures started 1 minute ago (Kim's) -> 9 minutes left.
		assertThat(throttle.blockedFor("sam@example.com", "198.51.100.1")).contains(Duration.ofMinutes(4));
		assertThat(throttle.blockedFor("sam@example.com", IP)).contains(Duration.ofMinutes(9));
	}

	@Test
	void missingEmailOrIpIsOnlyCountedForWhatIsThere() {
		fail(null, IP, 5);
		fail("  ", IP, 0);

		assertThat(throttle.blockedFor(null, IP)).isPresent();
		assertThat(throttle.blockedFor("sam@example.com", null)).isEmpty();
	}

	@Test
	void purgingForgetsPassedWindows() {
		fail("sam@example.com", IP, 1);
		clock.advance(Duration.ofMinutes(5));
		fail("kim@example.com", "198.51.100.1", 1);
		assertThat(throttle.trackedKeys()).isEqualTo(4);

		clock.advance(Duration.ofMinutes(6));
		throttle.purgeExpired();
		assertThat(throttle.trackedKeys()).as("only Kim's email and IP are recent").isEqualTo(2);
	}

	@Test
	void aVeryLongEmailIsStoredCut() {
		String longEmail = "a".repeat(5000) + "@example.com";
		fail(longEmail, IP, 3);

		assertThat(throttle.blockedFor(longEmail, "198.51.100.1")).isPresent();
	}

	private static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
