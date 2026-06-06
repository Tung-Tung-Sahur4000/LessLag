package tk.bridgersilk.lesslag.web.pages;

public final class HistoryPage {

	private HistoryPage() {
	}

	public static String render() {
		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>LessLag - Performance History</title>

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

					p {
						color: #a6a8b0;
					}
				</style>
			</head>

			<body>
				<main>
					<div class="brand">LessLag</div>
					<h1>Performance History</h1>
					<p>Historical TPS, MSPT, CPU and RAM graphs will appear here.</p>
				</main>
			</body>
			</html>
			""";
	}
}