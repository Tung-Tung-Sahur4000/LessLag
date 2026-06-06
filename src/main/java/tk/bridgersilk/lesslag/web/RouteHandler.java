package tk.bridgersilk.lesslag.web;

import com.sun.net.httpserver.HttpExchange;

public interface RouteHandler {
	HttpResponse handle(HttpExchange exchange) throws Exception;
}