// Vendored from pi-web-access 0.44.0 (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.
export function normalizeSearchResultCount(value: number | undefined): number {
	if (typeof value !== "number" || !Number.isFinite(value)) return 5;
	return Math.max(1, Math.min(Math.floor(value), 20));
}
