import { defineCollection } from "astro:content";
import { glob } from "astro/loaders";
import { docsSchema, i18nSchema } from "@astrojs/starlight/schema";

// The guide's Markdown lives in the repository's top-level docs/ folder, next to the code it describes. Entry IDs
// get a docs/ prefix because Starlight builds each page's URL from its ID, which puts the guide under /docs/.
export const collections = {
  docs: defineCollection({
    loader: glob({
      base: "../docs",
      pattern: "**/[^_]*.md",
      generateId: ({ entry }) => {
        const path = entry.replace(/\.md$/, "").replace(/(^|\/)index$/, "");
        return path ? `docs/${path}` : "docs";
      },
    }),
    schema: docsSchema(),
  }),
  // Starlight always reads its UI translation overrides from this collection. The guide is English only and keeps
  // Starlight's own strings, so one empty English entry stands in for a translations folder and stops Astro warning
  // that the collection is missing.
  i18n: defineCollection({ loader: () => [{ id: "en" }], schema: i18nSchema() }),
};
