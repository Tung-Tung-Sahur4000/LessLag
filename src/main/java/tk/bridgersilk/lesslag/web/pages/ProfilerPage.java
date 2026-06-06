package tk.bridgersilk.lesslag.web.pages;

public final class ProfilerPage {

	private ProfilerPage() {
	}

	public static String render() {
		return renderPlaceholder(
			"Live Profiler",
			"Profiler data will be displayed here."
		);
	}

	private static String renderPlaceholder(
		String title,
		String description
	) {
		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>LessLag - %s</title>

				<style>
					body {
						margin: 0;
						min-height: 100vh;
						display: grid;
						place-items: center;
						background: #111216;
						color: #f5f5f5;
						font-family: Inter, Arial, sans-serif;
					}

					main {
						width: min(600px, calc(100%% - 40px));
						padding: 36px;
						border: 1px solid #343741;
						border-radius: 16px;
						background: #1a1c22;
					}

					.brand {
						color: #ff5c5c;
						font-weight: 700;
					}

					h1 {
						margin-bottom: 8px;
					}

					p {
						color: #a6a8b0;
					}
				</style>
			</head>

			<body>
				<main>
					<div class="brand">LessLag</div>
					<h1>%s</h1>
					<p>%s</p>
				</main>
			</body>
			</html>
			""".formatted(title, title, description);
	}
}