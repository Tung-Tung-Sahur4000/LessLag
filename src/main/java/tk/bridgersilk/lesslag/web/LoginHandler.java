package tk.bridgersilk.lesslag.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.bukkit.configuration.file.FileConfiguration;

import com.sun.net.httpserver.HttpExchange;

import tk.bridgersilk.lesslag.LessLag;
import tk.bridgersilk.lesslag.web.pages.LoginPage;

public class LoginHandler implements RouteHandler {

	private static final int MAX_LOGIN_BODY_SIZE = 8192;

	private final LessLag plugin;
	private final WebServer webServer;

	public LoginHandler(LessLag plugin, WebServer webServer) {
		this.plugin = plugin;
		this.webServer = webServer;
	}

	@Override
	public HttpResponse handle(HttpExchange exchange) throws Exception {
		String method = exchange.getRequestMethod();

		if ("GET".equalsIgnoreCase(method)) {
			return handleGet(exchange);
		}

		if ("POST".equalsIgnoreCase(method)) {
			return handlePost(exchange);
		}

		return new HttpResponse(
			405,
			"text/plain; charset=UTF-8",
			"Method Not Allowed"
		).withHeader("Allow", "GET, POST");
	}

	private HttpResponse handleGet(HttpExchange exchange) {
		Map<String, String> query =
			WebServer.parseQuery(exchange.getRequestURI().getRawQuery());

		String target = webServer.sanitizeTarget(
			query.getOrDefault("target", "/profiler")
		);

		String token = query.getOrDefault("token", "");
		boolean invalidCredentials =
			"invalid".equalsIgnoreCase(query.get("error"));

		if (!webServer.isAuthenticationEnabled()) {
			return HttpResponse.redirect(target);
		}

		String sessionId = webServer.getSessionCookie(exchange);

		if (webServer.getSessionManager().isValid(sessionId)) {
			return HttpResponse.redirect(target);
		}

		return new HttpResponse(
			200,
			"text/html; charset=UTF-8",
			LoginPage.render(
				target,
				token,
				invalidCredentials
			)
		);
	}

	private HttpResponse handlePost(HttpExchange exchange)
		throws IOException {

		if (!webServer.isAuthenticationEnabled()) {
			return HttpResponse.redirect("/profiler");
		}

		byte[] bodyBytes = exchange
			.getRequestBody()
			.readNBytes(MAX_LOGIN_BODY_SIZE + 1);

		if (bodyBytes.length > MAX_LOGIN_BODY_SIZE) {
			return new HttpResponse(
				413,
				"text/plain; charset=UTF-8",
				"Request body is too large."
			);
		}

		String requestBody = new String(
			bodyBytes,
			StandardCharsets.UTF_8
		);

		Map<String, String> form = WebServer.parseQuery(requestBody);

		String username = form.getOrDefault("username", "");
		String password = form.getOrDefault("password", "");
		String target = webServer.sanitizeTarget(
			form.getOrDefault("target", "/profiler")
		);
		String token = form.getOrDefault("token", "");

		FileConfiguration config =
			plugin.getConfigManager().getConfig();

		String configuredUsername = config.getString(
			"web_interface.authentication.username",
			"admin"
		);

		String configuredPassword = config.getString(
			"web_interface.authentication.password",
			""
		);

		boolean credentialsValid =
			secureEquals(username, configuredUsername)
				&& secureEquals(password, configuredPassword);

		if (!credentialsValid) {
			String redirectUrl =
				"/login?target="
					+ WebServer.urlEncode(target)
					+ "&token="
					+ WebServer.urlEncode(token)
					+ "&error=invalid";

			return HttpResponse.redirect(redirectUrl);
		}

		String sessionId =
			webServer.getSessionManager().createSession();

		if (
			!token.isBlank()
				&& webServer
					.getTokenManager()
					.isValid(token, target)
		) {
			webServer.getTokenManager().invalidate(token);
		}

		String cookie = webServer.createSessionCookie(sessionId);

		return HttpResponse
			.redirect(target)
			.withHeader("Set-Cookie", cookie);
	}

	private boolean secureEquals(String first, String second) {
		byte[] firstBytes = first.getBytes(StandardCharsets.UTF_8);
		byte[] secondBytes = second.getBytes(StandardCharsets.UTF_8);

		return MessageDigest.isEqual(firstBytes, secondBytes);
	}
}