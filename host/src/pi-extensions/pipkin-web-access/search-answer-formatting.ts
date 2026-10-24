// Vendored from pi-web-access 0.44.0 (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.
interface AnswerResult {
	title: string;
	url: string;
	snippet: string;
}

export function formatSearchResultsAsAnswer(results: readonly AnswerResult[]): string {
	return results.map((result) => result.snippet
		? `${result.snippet}\nSource: ${result.title} (${result.url})`
		: `Source: ${result.title} (${result.url})`).join("\n\n");
}
