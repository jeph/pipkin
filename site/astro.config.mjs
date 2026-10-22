import sitemap from "@astrojs/sitemap";
import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";

// Canonical URLs, the sitemap and Open Graph images need an absolute origin. SITE_URL wins when set; on Vercel the
// project's production domain is the fallback, so previews of dev still point search engines at the public site.
const productionHost = process.env.VERCEL_PROJECT_PRODUCTION_URL;
const site =
  process.env.SITE_URL ?? (productionHost ? `https://${productionHost}` : "http://localhost:4321");

export default defineConfig({
  site,
  integrations: [
    sitemap(),
    // The guide under /docs/. Its pages come from the repository's docs/ folder; see src/content.config.ts.
    starlight({
      title: "Pipkin",
      description: "How to set up Pipkin, connect a model, and configure the Pi agent on your Android phone.",
      logo: { src: "./public/favicon.svg" },
      favicon: "/favicon.svg",
      social: [{ icon: "github", label: "GitHub", href: "https://github.com/jeph/pipkin" }],
      // Link previews of guide pages use the landing page's share image.
      head: [
        { tag: "meta", attrs: { property: "og:image", content: new URL("/og.png", site).href } },
        { tag: "meta", attrs: { name: "twitter:card", content: "summary_large_image" } },
      ],
      // The guide uses the landing page's fonts, themes and theme menu.
      customCss: ["./src/styles/theme.css", "./src/styles/docs.css"],
      components: {
        ThemeProvider: "./src/components/DocsThemeProvider.astro",
        ThemeSelect: "./src/components/ThemeSelect.astro",
      },
      markdown: { processedDirs: ["../docs/"] },
      // Starlight's 404 page would answer for the whole site, landing page included, so Vercel's default stays.
      disable404Route: true,
      sidebar: [
        { label: "Overview", slug: "docs" },
        { label: "Get started", slug: "docs/get-started" },
        { label: "Connect a model", slug: "docs/providers" },
        { label: "Configure the agent", slug: "docs/configure" },
        { label: "Extensions", slug: "docs/extensions" },
        { label: "Run in the background", slug: "docs/background" },
        { label: "Acknowledgements", slug: "docs/acknowledgements" },
      ],
    }),
  ],
  build: {
    inlineStylesheets: "always",
  },
});
