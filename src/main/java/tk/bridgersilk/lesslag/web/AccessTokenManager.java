package tk.bridgersilk.lesslag.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AccessTokenManager {

	private static class AccessToken {

		private final String targetPath;
		private final long expirationTime;

		private AccessToken(String targetPath, long expirationTime) {
			this.targetPath = targetPath;
			this.expirationTime = expirationTime;
		}
	}

	private final Map<String, AccessToken> tokens =
		new ConcurrentHashMap<>();

	private final SecureRandom secureRandom = new SecureRandom();
	private final long expirationMillis;

	public AccessTokenManager(int expirationMinutes) {
		int safeMinutes = Math.max(1, expirationMinutes);
		this.expirationMillis = safeMinutes * 60L * 1000L;
	}

	public String createToken(String targetPath) {
		byte[] tokenBytes = new byte[32];
		secureRandom.nextBytes(tokenBytes);

		String token = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(tokenBytes);

		tokens.put(
			token,
			new AccessToken(
				targetPath,
				System.currentTimeMillis() + expirationMillis
			)
		);

		removeExpiredTokens();

		return token;
	}

	public boolean isValid(String token, String targetPath) {
		if (token == null || targetPath == null) {
			return false;
		}

		AccessToken accessToken = tokens.get(token);

		if (accessToken == null) {
			return false;
		}

		if (System.currentTimeMillis() > accessToken.expirationTime) {
			tokens.remove(token);
			return false;
		}

		return accessToken.targetPath.equals(targetPath);
	}

	public void invalidate(String token) {
		if (token != null) {
			tokens.remove(token);
		}
	}

	private void removeExpiredTokens() {
		long currentTime = System.currentTimeMillis();

		tokens.entrySet().removeIf(
			entry -> currentTime > entry.getValue().expirationTime
		);
	}
}