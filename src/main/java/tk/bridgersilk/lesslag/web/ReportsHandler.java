package tk.bridgersilk.lesslag.web;

import com.sun.net.httpserver.HttpExchange;

import tk.bridgersilk.lesslag.web.pages.ReportsPage;

public class ReportsHandler implements RouteHandler {

	private final WebServer webServer;

	public ReportsHandler(WebServer webServer) {
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
			webServer.requireAuthentication(exchange, "/reports");

		if (authenticationResponse != null) {
			return authenticationResponse;
		}

		return new HttpResponse(
			200,
			"text/html; charset=UTF-8",
			ReportsPage.render()
		);
	}
}