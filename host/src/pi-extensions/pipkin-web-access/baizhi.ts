// Vendored from pi-web-access 0.46.0 (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.
import { existsSync, readFileSync } from "node:fs";
import { isIP } from "node:net";
import { activityMonitor } from "./activity.ts";
import { hasCredentialSource, redactCredential, resolveCredential } from "./credential-source.ts";
import { normalizeDomain } from "./domain-filter-normalization.ts";
import type { SearchOptions, SearchResponse } from "./perplexity.ts";
import { normalizeSearchResultCount } from "./search-result-count-normalization.ts";
import { getWebSearchConfigPath } from "./utils.ts";

const MCP_URL = "https://agent-toolkit.app.baizhi.cloud/mcp";
const SEARCH_TIMEOUT_MS = 60_000;
const CONFIG_PATH = getWebSearchConfigPath();

function credentialOptions() {
	let config: Record<string, unknown> = {};
	if (existsSync(CONFIG_PATH)) {
		try {
			const parsed: unknown = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
			if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error();
			config = parsed as Record<string, unknown>;
		} catch {
			// JSON parser errors may include a fragment containing a literal key.
			throw new Error(`Invalid configuration in ${CONFIG_PATH}: expected a JSON object`);
		}
	}
	return { provider: "Baizhi", configuredValue: config.baizhiApiKey, environmentValue: process.env.BAIZHI_API_KEY };
}

export function isBaizhiAvailable(): boolean {
	return hasCredentialSource(credentialOptions());
}

function searchArguments(query: string, options: SearchOptions): Record<string, unknown> {
	if (!query.trim()) throw new Error("Baizhi search query must not be empty");
	const domains: string[] = [];
	const excludeDomains: string[] = [];
	for (const raw of options.domainFilter ?? []) {
		const bare = raw.trim().replace(/^-/, "").trim();
		const domain = isIP(bare) ? bare : normalizeDomain(raw);
		if (!domain) throw new Error("Baizhi domain filter must contain a valid domain");
		const target = raw.trim().startsWith("-") ? excludeDomains : domains;
		if (!target.includes(domain)) target.push(domain);
	}
	return {
		query: query.trim(),
		count: normalizeSearchResultCount(options.numResults),
		need_summary: false,
		// The published discovery contract defaults to month, not unrestricted time.
		time_range: options.recencyFilter ?? "month",
		...(domains.length || excludeDomains.length ? { filter: {
			...(domains.length ? { domains } : {}),
			...(excludeDomains.length ? { exclude_domains: excludeDomains } : {}),
		} } : {}),
	};
}

export async function searchWithBaizhi(query: string, options: SearchOptions = {}): Promise<SearchResponse> {
	const args = searchArguments(query, options);
	const timeoutSignal = AbortSignal.timeout(SEARCH_TIMEOUT_MS);
	const signal = options.signal ? AbortSignal.any([timeoutSignal, options.signal]) : timeoutSignal;
	if (signal.aborted) throw new Error("Aborted");
	const apiKey = await resolveCredential({ ...credentialOptions(), signal });
	if (!apiKey) throw new Error("Baizhi API key not found. Set BAIZHI_API_KEY or baizhiApiKey in web-search.json. Get your own key at https://agent-toolkit.app.baizhi.cloud/");
	let headers: Headers;
	try { headers = new Headers({ Authorization: `Bearer ${apiKey}` }); }
	catch { throw new Error("Baizhi credential resolution failed: invalid-header-value"); }
	// Load the protocol client only when this explicit provider is selected.
	const [{ Client }, { StreamableHTTPClientTransport }] = await Promise.all([
		import("@modelcontextprotocol/sdk/client/index.js"),
		import("@modelcontextprotocol/sdk/client/streamableHttp.js"),
	]);
	let cleaningUp = false;
	const transport = new StreamableHTTPClientTransport(new URL(MCP_URL), {
		requestInit: { headers },
		fetch: async (url, init) => {
			// Fixed endpoint, no redirects: neither credentials nor session IDs can
			// be forwarded to another origin (including MCP discovery redirects).
			if (String(url) !== MCP_URL) throw new Error("Baizhi endpoint changed unexpectedly");
			const requestSignal = cleaningUp ? AbortSignal.timeout(1_500) : signal;
			return fetch(url, { ...init, redirect: "error", signal: !cleaningUp && init?.signal ? AbortSignal.any([init.signal, requestSignal]) : requestSignal });
		},
		reconnectionOptions: { maxRetries: 0, initialReconnectionDelay: 1_000, maxReconnectionDelay: 1_000, reconnectionDelayGrowFactor: 1 },
	});
	const client = new Client({ name: "pi-web-access-baizhi", version: "1.0.0" });
	const activityId = activityMonitor.logStart({ type: "api", query });
	try {
		await client.connect(transport, { signal, timeout: SEARCH_TIMEOUT_MS });
		const result = await client.callTool({ name: "websearch_search", arguments: args }, undefined, { signal, timeout: SEARCH_TIMEOUT_MS });
		if (result.isError) throw new Error("Baizhi search tool returned an error");
		// The public discovery snapshot documents inputs but no outputSchema.
		// Preserve the MCP payload instead of guessing result item field names.
		const parts: string[] = [];
		if (result.structuredContent !== undefined) parts.push(JSON.stringify(result.structuredContent, null, 2));
		for (const item of Array.isArray(result.content) ? result.content : []) {
			if (item.type === "text" && typeof item.text === "string" && item.text.trim()) parts.push(item.text);
		}
		if (!parts.length) throw new Error("Baizhi returned invalid response: no search text or structured content");
		const answer = redactCredential(redactCredential(parts.join("\n\n"), apiKey), JSON.stringify(apiKey).slice(1, -1));
		activityMonitor.logComplete(activityId, 200);
		return { answer, results: [] };
	} catch (err) {
		let message: string;
		if (options.signal?.aborted) message = "Aborted";
		else if (timeoutSignal.aborted) message = "Baizhi request timed out after 60s";
		else {
			const code = err && typeof err === "object" && "code" in err ? err.code : undefined;
			// Never expose remote HTTP/JSON-RPC bodies or nested SDK errors: they
			// can echo credentials in plain, encoded or transformed form.
			message = typeof code === "number" && Number.isInteger(code) && code >= 400 && code <= 599
				? `Baizhi HTTP ${code} request failed; check your API key, account and service status`
				: err instanceof TypeError ? "Baizhi network request failed"
				: err instanceof SyntaxError || code === -32700 || code === -32602 ? "Baizhi returned invalid response"
				: err instanceof Error && /^Baizhi (search tool returned an error|returned invalid response:)/.test(err.message) ? err.message
				: "Baizhi MCP request failed; check your connection, API key and account status";
		}
		activityMonitor.logError(activityId, message);
		throw new Error(message);
	} finally {
		cleaningUp = true;
		// Best effort, separately bounded DELETE; always close the local streams.
		try { await transport.terminateSession(); } catch { /* Server may not support DELETE. */ }
		await client.close();
	}
}
