package tk.bridgersilk.lesslag.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSessionManager {

	private final Map<String, Long> sessions =
		new ConcurrentHashMap<>();

	private final SecureRandom secureRandom = new SecureRandom();
	private final long timeoutMillis;

	public WebSessionManager(int timeoutMinutes) {
		int safeMinutes = Math.max(1, timeoutMinutes);
		this.timeoutMillis = safeMinutes * 60L * 1000L;
	}

	public String createSession() {
		byte[] sessionBytes = new byte[32];
		secureRandom.nextBytes(sessionBytes);

		String sessionId = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(sessionBytes);

		refreshSession(sessionId);
		removeExpiredSessions();

		return sessionId;
	}

	public boolean isValid(String sessionId) {
		if (sessionId == null) {
			return false;
		}

		Long expirationTime = sessions.get(sessionId);

		if (expirationTime == null) {
			return false;
		}

		if (System.currentTimeMillis() > expirationTime) {
			sessions.remove(sessionId);
			return false;
		}

		// Session timeout based on inactivity.
		refreshSession(sessionId);
		return true;
	}

	public void invalidate(String sessionId) {
		if (sessionId != null) {
			sessions.remove(sessionId);
		}
	}

	public long getTimeoutSeconds() {
		return timeoutMillis / 1000L;
	}

	private void refreshSession(String sessionId) {
		sessions.put(
			sessionId,
			System.currentTimeMillis() + timeoutMillis
		);
	}

	private void removeExpiredSessions() {
		long currentTime = System.currentTimeMillis();

		sessions.entrySet().removeIf(
			entry -> currentTime > entry.getValue()
		);
	}
}