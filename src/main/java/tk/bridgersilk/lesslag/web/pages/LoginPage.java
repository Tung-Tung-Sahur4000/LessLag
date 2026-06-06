package tk.bridgersilk.lesslag.web.pages;

public final class LoginPage {

	private LoginPage() {
	}

	public static String render(
		String target,
		String accessToken,
		boolean invalidCredentials
	) {
		String escapedTarget = escapeHtml(target);
		String escapedToken = escapeHtml(
			accessToken == null ? "" : accessToken
		);

		String error = invalidCredentials
			? """
				<div class="error">
					Incorrect username or password.
				</div>
				"""
			: "";

		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>LessLag Login</title>

				<style>
					* {
						box-sizing: border-box;
					}

					body {
						margin: 0;
						min-height: 100vh;
						display: flex;
						align-items: center;
						justify-content: center;
						padding: 24px;
						background:
							radial-gradient(circle at top, #252934, #101114 60%%);
						color: #f4f4f5;
						font-family:
							Inter,
							Arial,
							sans-serif;
					}

					.login-card {
						width: 100%%;
						max-width: 390px;
						padding: 32px;
						border: 1px solid #343741;
						border-radius: 16px;
						background: rgba(26, 28, 34, 0.96);
						box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
					}

					.brand {
						margin-bottom: 8px;
						color: #ff5c5c;
						font-size: 14px;
						font-weight: 700;
						letter-spacing: 1.5px;
						text-transform: uppercase;
					}

					h1 {
						margin: 0 0 8px;
						font-size: 28px;
					}

					.subtitle {
						margin: 0 0 24px;
						color: #a6a8b0;
						line-height: 1.5;
					}

					label {
						display: block;
						margin: 16px 0 7px;
						font-size: 14px;
						font-weight: 600;
					}

					input {
						width: 100%%;
						padding: 12px 13px;
						border: 1px solid #40434d;
						border-radius: 9px;
						outline: none;
						background: #111216;
						color: #ffffff;
						font-size: 15px;
					}

					input:focus {
						border-color: #ff5c5c;
						box-shadow: 0 0 0 3px rgba(255, 92, 92, 0.14);
					}

					button {
						width: 100%%;
						margin-top: 24px;
						padding: 12px;
						border: 0;
						border-radius: 9px;
						background: #e84f4f;
						color: #ffffff;
						font-size: 15px;
						font-weight: 700;
						cursor: pointer;
					}

					button:hover {
						background: #f35c5c;
					}

					.error {
						margin: 18px 0 4px;
						padding: 11px 12px;
						border: 1px solid #8f3d3d;
						border-radius: 8px;
						background: rgba(143, 61, 61, 0.2);
						color: #ffb0b0;
						font-size: 14px;
					}
				</style>
			</head>

			<body>
				<main class="login-card">
					<div class="brand">LessLag</div>
					<h1>Administrator login</h1>

					<p class="subtitle">
						Sign in to access the server performance interface.
					</p>

					%s

					<form method="POST" action="/login">
						<input
							type="hidden"
							name="target"
							value="%s"
						>

						<input
							type="hidden"
							name="token"
							value="%s"
						>

						<label for="username">Username</label>
						<input
							id="username"
							name="username"
							type="text"
							autocomplete="username"
							required
							autofocus
						>

						<label for="password">Password</label>
						<input
							id="password"
							name="password"
							type="password"
							autocomplete="current-password"
							required
						>

						<button type="submit">Sign in</button>
					</form>
				</main>
			</body>
			</html>
			""".formatted(
				error,
				escapedTarget,
				escapedToken
			);
	}

	private static String escapeHtml(String value) {
		if (value == null) {
			return "";
		}

		return value
			.replace("&", "&amp;")
			.replace("\"", "&quot;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("'", "&#39;");
	}
}