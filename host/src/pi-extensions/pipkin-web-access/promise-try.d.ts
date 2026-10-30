// Vendored from pi-web-access 0.46.0 (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.
declare module "promise.try" {
	function promiseTry<T>(callback: () => T | PromiseLike<T>): Promise<T>;
	namespace promiseTry {
		function shim(): void;
	}
	export default promiseTry;
}
