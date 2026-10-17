import sitemap from "@astrojs/sitemap";
import { defineConfig } from "astro/config";

// Canonical URLs, the sitemap and Open Graph images need an absolute origin. SITE_URL wins when set; on Vercel the
// project's production domain is the fallback, so previews of dev still point search engines at the public site.
const productionHost = process.env.VERCEL_PROJECT_PRODUCTION_URL;
const site =
  process.env.SITE_URL ?? (productionHost ? `https://${productionHost}` : "http://localhost:4321");

export default defineConfig({
  site,
  integrations: [sitemap()],
  build: {
    inlineStylesheets: "always",
  },
});
