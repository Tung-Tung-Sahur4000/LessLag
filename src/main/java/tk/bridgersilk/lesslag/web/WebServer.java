package tk.bridgersilk.lesslag.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tk.bridgersilk.lesslag.LessLag;

public class WebServer {

	private static final String SESSION_COOKIE_NAME =
		"LESSLAG_SESSION";

	private final LessLag plugin;
	private final Map<String, RouteHandler> routes =
		new HashMap<>();

	private HttpServer server;
	private ExecutorService executorService;

	private AccessTokenManager tokenManager;
	private WebSessionManager sessionManager;

	private String configuredHost;
	private int configuredPort;

	public WebServer(LessLag plugin) {
		this.plugin = plugin;
	}

	public synchronized void start() {
		if (server != null) {
			return;
		}

		FileConfiguration config =
			plugin.getConfigManager().getConfig();

		if (!config.getBoolean("web_interface.enabled", true)) {
			plugin.getLogger().info(
				"Web interface is disabled in config.yml."
			);
			return;
		}

		boolean sslEnabled = config.getBoolean(
			"web_interface.ssl.enabled",
			false
		);

		if (sslEnabled) {
			plugin.getLogger().severe(
				"LessLag web interface could not start: "
					+ "SSL is enabled, but no certificate or "
					+ "keystore configuration exists."
			);

			plugin.getLogger().severe(
				"Disable web_interface.ssl.enabled until "
					+ "certificate settings are added."
			);

			return;
		}

		configuredHost = config.getString(
			"web_interface.host",
			"0.0.0.0"
		);

		if (
			configuredHost == null
				|| configuredHost.isBlank()
		) {
			configuredHost = "0.0.0.0";
		}

		configuredPort = config.getInt(
			"web_interface.port",
			25580
		);

		if (
			configuredPort < 1
				|| configuredPort > 65535
		) {
			plugin.getLogger().warning(
				"Invalid web interface port "
					+ configuredPort
					+ ". Using 25580."
			);

			configuredPort = 25580;
		}

		tokenManager = new AccessTokenManager(
			config.getInt(
				"web_interface.access_link_expiration_minutes",
				30
			)
		);

		sessionManager = new WebSessionManager(
			config.getInt(
				"web_interface.session_timeout_minutes",
				60
			)
		);

		registerDefaultRoutes();

		try {
			server = HttpServer.create(
				new InetSocketAddress(
					configuredHost,
					configuredPort
				),
				0
			);

			server.createContext("/", this::handleRequest);

			executorService = Executors.newCachedThreadPool(
				runnable -> {
					Thread thread = new Thread(
						runnable,
						"LessLag-Web"
					);

					thread.setDaemon(true);
					return thread;
				}
			);

			server.setExecutor(executorService);
			server.start();

			plugin.getLogger().info(
				"Web interface started on http://"
					+ configuredHost
					+ ":"
					+ configuredPort
			);
		} catch (IOException exception) {
			server = null;

			if (executorService != null) {
				executorService.shutdownNow();
				executorService = null;
			}

			plugin.getLogger().severe(
				"Could not start the LessLag web interface: "
					+ exception.getMessage()
			);

			exception.printStackTrace();
		}
	}

	public synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}

		if (executorService != null) {
			executorService.shutdownNow();
			executorService = null;
		}

		routes.clear();

		plugin.getLogger().info(
			"LessLag web interface stopped."
		);
	}

	public boolean isRunning() {
		return server != null;
	}

	public void registerRoute(
		String path,
		RouteHandler handler
	) {
		routes.put(path, handler);
	}

	public String generateAccessLink(String pageName) {
		if (!isRunning()) {
			return null;
		}

		String targetPath = pageNameToPath(pageName);

		if (targetPath == null) {
			return null;
		}

		FileConfiguration config =
			plugin.getConfigManager().getConfig();

		boolean generateAccessLinks = config.getBoolean(
			"web_interface.generate_access_links",
			true
		);

		String baseUrl = getBaseUrl();

		if (!generateAccessLinks) {
			return baseUrl + targetPath;
		}

		String token =
			tokenManager.createToken(targetPath);

		return baseUrl
			+ targetPath
			+ "?token="
			+ urlEncode(token);
	}

	public HttpResponse requireAuthentication(
		HttpExchange exchange,
		String targetPath
	) {
		if (!isAuthenticationEnabled()) {
			return null;
		}

		String sessionId = getSessionCookie(exchange);

		if (sessionManager.isValid(sessionId)) {
			return null;
		}

		Map<String, String> query =
			parseQuery(exchange.getRequestURI().getRawQuery());

		String token = query.getOrDefault("token", "");

		String redirectUrl =
			"/login?target="
				+ urlEncode(targetPath);

		if (
			!token.isBlank()
				&& tokenManager.isValid(token, targetPath)
		) {
			redirectUrl +=
				"&token="
					+ urlEncode(token);
		}

		return HttpResponse.redirect(redirectUrl);
	}

	public boolean isAuthenticationEnabled() {
		return plugin
			.getConfigManager()
			.getConfig()
			.getBoolean(
				"web_interface.authentication.enabled",
				false
			);
	}

	public String getSessionCookie(HttpExchange exchange) {
		String cookieHeader =
			exchange.getRequestHeaders().getFirst("Cookie");

		if (cookieHeader == null || cookieHeader.isBlank()) {
			return null;
		}

		String[] cookies = cookieHeader.split(";");

		for (String cookie : cookies) {
			String[] parts = cookie.trim().split("=", 2);

			if (
				parts.length == 2
					&& SESSION_COOKIE_NAME.equals(parts[0])
			) {
				return parts[1];
			}
		}

		return null;
	}

	public String createSessionCookie(String sessionId) {
		StringBuilder cookie = new StringBuilder();

		cookie.append(SESSION_COOKIE_NAME)
			.append("=")
			.append(sessionId)
			.append("; Path=/")
			.append("; HttpOnly")
			.append("; SameSite=Strict")
			.append("; Max-Age=")
			.append(sessionManager.getTimeoutSeconds());

		boolean sslEnabled = plugin
			.getConfigManager()
			.getConfig()
			.getBoolean(
				"web_interface.ssl.enabled",
				false
			);

		if (sslEnabled) {
			cookie.append("; Secure");
		}

		return cookie.toString();
	}

	public String sanitizeTarget(String target) {
		if ("/profiler".equals(target)) {
			return "/profiler";
		}

		if ("/history".equals(target)) {
			return "/history";
		}

		if ("/reports".equals(target)) {
			return "/reports";
		}

		return "/profiler";
	}

	public AccessTokenManager getTokenManager() {
		return tokenManager;
	}

	public WebSessionManager getSessionManager() {
		return sessionManager;
	}

	public String getBaseUrl() {
		String linkHost = configuredHost;

		if (
			"0.0.0.0".equals(linkHost)
				|| "::".equals(linkHost)
		) {
			String minecraftServerIp = Bukkit.getIp();

			if (
				minecraftServerIp != null
					&& !minecraftServerIp.isBlank()
			) {
				linkHost = minecraftServerIp;
			} else {
				linkHost = "localhost";
			}
		}

		if (
			linkHost.contains(":")
				&& !linkHost.startsWith("[")
		) {
			linkHost = "[" + linkHost + "]";
		}

		return "http://"
			+ linkHost
			+ ":"
			+ configuredPort;
	}

	private void registerDefaultRoutes() {
		routes.clear();

		registerRoute(
			"/login",
			new LoginHandler(plugin, this)
		);

		registerRoute(
			"/profiler",
			new ProfilerHandler(this)
		);

		registerRoute(
			"/history",
			new HistoryHandler(this)
		);

		registerRoute(
			"/reports",
			new ReportsHandler(this)
		);

		registerRoute(
			"/",
			exchange -> HttpResponse.redirect("/profiler")
		);
	}

	private void handleRequest(HttpExchange exchange)
		throws IOException {

		String path = exchange.getRequestURI().getPath();
		RouteHandler handler = routes.get(path);

		if (handler == null) {
			sendResponse(
				exchange,
				new HttpResponse(
					404,
					"text/html; charset=UTF-8",
					"<h1>404 - Page not found</h1>"
				)
			);

			return;
		}

		try {
			HttpResponse response =
				handler.handle(exchange);

			sendResponse(exchange, response);
		} catch (Exception exception) {
			plugin.getLogger().severe(
				"Web request failed for "
					+ path
					+ ": "
					+ exception.getMessage()
			);

			exception.printStackTrace();

			sendResponse(
				exchange,
				new HttpResponse(
					500,
					"text/html; charset=UTF-8",
					"<h1>500 - Internal server error</h1>"
				)
			);
		}
	}

	private void sendResponse(
		HttpExchange exchange,
		HttpResponse response
	) throws IOException {

		byte[] data = response
			.getBody()
			.getBytes(StandardCharsets.UTF_8);

		exchange.getResponseHeaders().set(
			"Content-Type",
			response.getContentType()
		);

		exchange.getResponseHeaders().set(
			"X-Content-Type-Options",
			"nosniff"
		);

		exchange.getResponseHeaders().set(
			"X-Frame-Options",
			"DENY"
		);

		exchange.getResponseHeaders().set(
			"Referrer-Policy",
			"no-referrer"
		);

		for (
			Map.Entry<String, String> header
				: response.getHeaders().entrySet()
		) {
			exchange.getResponseHeaders().set(
				header.getKey(),
				header.getValue()
			);
		}

		boolean headRequest =
			"HEAD".equalsIgnoreCase(exchange.getRequestMethod());

		exchange.sendResponseHeaders(
			response.getStatusCode(),
			headRequest ? -1 : data.length
		);

		if (!headRequest) {
			try (
				OutputStream output =
					exchange.getResponseBody()
			) {
				output.write(data);
			}
		} else {
			exchange.close();
		}
	}

	private String pageNameToPath(String pageName) {
		if (pageName == null) {
			return null;
		}

		return switch (pageName.toLowerCase()) {
			case "profiler" -> "/profiler";
			case "history" -> "/history";
			case "reports" -> "/reports";
			default -> null;
		};
	}

	public static Map<String, String> parseQuery(
		String rawQuery
	) {
		Map<String, String> parameters =
			new LinkedHashMap<>();

		if (rawQuery == null || rawQuery.isBlank()) {
			return parameters;
		}

		String[] pairs = rawQuery.split("&");

		for (String pair : pairs) {
			if (pair.isBlank()) {
				continue;
			}

			String[] parts = pair.split("=", 2);

			String key = urlDecode(parts[0]);
			String value = parts.length > 1
				? urlDecode(parts[1])
				: "";

			parameters.put(key, value);
		}

		return parameters;
	}

	public static String urlEncode(String value) {
		return URLEncoder.encode(
			value == null ? "" : value,
			StandardCharsets.UTF_8
		);
	}

	public static String urlDecode(String value) {
		return URLDecoder.decode(
			value == null ? "" : value,
			StandardCharsets.UTF_8
		);
	}
}