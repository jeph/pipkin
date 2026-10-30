// Vendored from pi-web-access 0.46.0 (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.
import { existsSync, readFileSync } from "node:fs";
import { activityMonitor } from "./activity.ts";
import { redactCredential, resolveCredential } from "./credential-source.ts";
import { normalizeDomain } from "./domain-filter-normalization.ts";
import type { SearchOptions, SearchResponse } from "./perplexity.ts";
import { formatSearchResultsAsAnswer } from "./search-answer-formatting.ts";
import { normalizeSearchResultCount } from "./search-result-count-normalization.ts";
import { fetchWithCredentialRedirects, getWebSearchConfigPath, resolveApiBaseUrl } from "./utils.ts";

// 9router (https://github.com/decolua/9router) is a self-hosted router whose POST /v1/search
// fronts many search APIs. It has no official host, so the base URL is required.
const CONFIG_PATH = getWebSearchConfigPath();
const SEARCH_TIMEOUT_MS = 30_000;

function loadConfig(): Record<string, unknown> {
	if (!existsSync(CONFIG_PATH)) return {};
	let parsed: unknown;
	// JSON parse errors can quote credentials from the file, so they are not echoed.
	try { parsed = JSON.parse(readFileSync(CONFIG_PATH, "utf-8")); } catch { parsed = undefined; }
	if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`Invalid config in ${CONFIG_PATH}: expected a JSON object`);
	return parsed as Record<string, unknown>;
}

export function isNineRouterAvailable(): boolean {
	const base = process.env.NINEROUTER_BASE_URL ?? loadConfig().ninerouterBaseUrl;
	return typeof base === "string" && base.trim().length > 0;
}

export async function searchWithNineRouter(query: string, options: SearchOptions = {}): Promise<SearchResponse> {
	const config = loadConfig();
	const base = resolveApiBaseUrl({ configKey: "ninerouterBaseUrl", configuredValue: config.ninerouterBaseUrl, defaultValue: "", environmentKey: "NINEROUTER_BASE_URL", environmentValue: process.env.NINEROUTER_BASE_URL });
	if (!base) throw new Error(`9router base URL not found. Set ninerouterBaseUrl in ${CONFIG_PATH} or NINEROUTER_BASE_URL (e.g. https://your-router/v1)`);
	const provider = process.env.NINEROUTER_SEARCH_PROVIDER || config.ninerouterProvider || "serper";
	if (typeof provider !== "string") throw new Error(`ninerouterProvider in ${CONFIG_PATH} must be a string`);
	// The key is optional: a self-hosted router may run with auth disabled.
	const apiKey = await resolveCredential({ provider: "9router", configuredValue: config.ninerouterApiKey, environmentValue: process.env.NINEROUTER_API_KEY, signal: options.signal });
	const include: string[] = [];
	const exclude: string[] = [];
	for (const raw of options.domainFilter ?? []) {
		const domain = normalizeDomain(raw);
		if (domain) (raw.trim().startsWith("-") ? exclude : include).push(domain);
	}
	const numResults = normalizeSearchResultCount(options.numResults);
	const timeout = AbortSignal.timeout(SEARCH_TIMEOUT_MS);
	const activityId = activityMonitor.logStart({ type: "api", query });
	let data: { results?: unknown; answer?: unknown } | null;
	try {
		const response = await fetchWithCredentialRedirects(`${base}/search`, {
			method: "POST",
			headers: { ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}), "Content-Type": "application/json", Accept: "application/json" },
			body: JSON.stringify({
				provider,
				query,
				max_results: numResults,
				...(options.recencyFilter ? { time_range: options.recencyFilter } : {}),
				...(include.length ? { domain_filter: include } : {}),
			}),
			signal: options.signal ? AbortSignal.any([timeout, options.signal]) : timeout,
		}, ["Authorization"]);
		// The status stays in the message so searchRouting can classify quota and transient failures.
		if (!response.ok) throw new Error(`9router error ${response.status}: ${redactCredential(await response.text(), apiKey).slice(0, 300)}`);
		// Syntax errors can quote the body, so they are not echoed; a stalled body read reaches the timeout check below.
		data = await response.json().catch((err: unknown) => { throw err instanceof SyntaxError ? new Error("9router returned invalid JSON") : err; });
		activityMonitor.logComplete(activityId, response.status);
	} catch (err) {
		// Node reports the deadline as "aborted due to timeout", which routing would treat as a cancellation.
		if (!options.signal?.aborted && (timeout.aborted || (err instanceof Error && err.name === "TimeoutError"))) {
			const message = `9router request timed out after ${SEARCH_TIMEOUT_MS / 1000}s`;
			activityMonitor.logError(activityId, message);
			throw new Error(message);
		}
		const message = redactCredential(err instanceof Error ? err.message : String(err), apiKey);
		activityMonitor.logError(activityId, message);
		if (err instanceof Error && err.message === message) throw err;
		throw new Error(message);
	}
	if (!Array.isArray(data?.results)) throw new Error("9router returned invalid response: expected results array");
	const results: SearchResponse["results"] = [];
	for (const entry of data.results as { title?: unknown; url?: unknown; snippet?: unknown }[]) {
		let url: URL;
		try { url = new URL(String(entry?.url)); } catch { continue; }
		if (url.protocol !== "https:" && url.protocol !== "http:") continue;
		const matches = (domain: string) => url.hostname === domain || url.hostname.endsWith(`.${domain}`);
		// Not every upstream honours domain_filter, and exclusions are only applied here.
		if (exclude.some(matches) || (include.length && !include.some(matches))) continue;
		const title = typeof entry.title === "string" && entry.title.trim() ? entry.title.trim() : `Source ${results.length + 1}`;
		results.push({ title, url: url.href, snippet: typeof entry.snippet === "string" ? entry.snippet : "" });
		if (results.length >= numResults) break;
	}
	const answer = typeof data.answer === "string" ? data.answer.trim() : "";
	return { answer: answer || formatSearchResultsAsAnswer(results), results };
}
