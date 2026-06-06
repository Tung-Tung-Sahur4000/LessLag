package tk.bridgersilk.lesslag.web;

import com.sun.net.httpserver.HttpExchange;

import tk.bridgersilk.lesslag.web.pages.HistoryPage;

public class HistoryHandler implements RouteHandler {

	private final WebServer webServer;

	public HistoryHandler(WebServer webServer) {
		this.webServer = webServer;
	}

	@Override
	public HttpResponse handle(HttpExchange exchange) {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			return new HttpResponse(
				405,
				"text/plain; charset=UTF-8",
				"Method Not Allowed"
			).withHeader("Allow", "GET");
		}

		HttpResponse authenticationResponse =
			webServer.requireAuthentication(exchange, "/history");

		if (authenticationResponse != null) {
			return authenticationResponse;
		}

		return new HttpResponse(
			200,
			"text/html; charset=UTF-8",
			HistoryPage.render()
		);
	}
}