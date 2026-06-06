package tk.bridgersilk.lesslag.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponse {

	private final int statusCode;
	private final String contentType;
	private final String body;
	private final Map<String, String> headers;

	public HttpResponse(int statusCode, String contentType, String body) {
		this(statusCode, contentType, body, Collections.emptyMap());
	}

	public HttpResponse(
		int statusCode,
		String contentType,
		String body,
		Map<String, String> headers
	) {
		this.statusCode = statusCode;
		this.contentType = contentType;
		this.body = body == null ? "" : body;
		this.headers = new LinkedHashMap<>(headers);
	}

	public static HttpResponse redirect(String location) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Location", location);

		return new HttpResponse(
			303,
			"text/plain; charset=UTF-8",
			"",
			headers
		);
	}

	public HttpResponse withHeader(String name, String value) {
		Map<String, String> updatedHeaders = new LinkedHashMap<>(headers);
		updatedHeaders.put(name, value);

		return new HttpResponse(
			statusCode,
			contentType,
			body,
			updatedHeaders
		);
	}

	public int getStatusCode() {
		return statusCode;
	}

	public String getContentType() {
		return contentType;
	}

	public String getBody() {
		return body;
	}

	public Map<String, String> getHeaders() {
		return Collections.unmodifiableMap(headers);
	}
}