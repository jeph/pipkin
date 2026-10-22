// Inlined at the top of every page, on the landing page by Base.astro and in the guide by DocsThemeProvider.astro, so a
// picked theme applies before the page paints instead of flashing the system's first. The theme menu
// (scripts/theme-select.ts) changes it through window.pipkinTheme.
//
// The themes are the app's. System, the default, leaves data-palette unset so the CSS follows the system between
// Porcelain and Stone, as the app does. data-theme always holds the resolved light or dark, which the guide's Starlight
// styles key on.
window.pipkinTheme = (() => {
  const key = "pipkin-theme";
  /** Each theme's scheme and page color, which also tints the browser's address bar. */
  const themes = {
    porcelain: { scheme: "light", page: "#f6f7fb" },
    clay: { scheme: "light", page: "#fbf5ec" },
    cobalt: { scheme: "dark", page: "#0c0f1a" },
    stone: { scheme: "dark", page: "#1c1d20" },
    obsidian: { scheme: "dark", page: "#0b0b0c" },
  };
  const root = document.documentElement;
  const darkMode = matchMedia("(prefers-color-scheme: dark)");

  const stored = () => {
    try {
      const theme = localStorage.getItem(key);
      return theme !== null && Object.hasOwn(themes, theme) ? theme : "system";
    } catch {
      return "system";
    }
  };

  /** The theme [choice] shows, with [dark] saying whether the system is in dark mode. */
  const resolve = (choice, dark) => (choice === "system" ? (dark ? "stone" : "porcelain") : choice);

  const apply = (choice) => {
    if (choice === "system") delete root.dataset.palette;
    else root.dataset.palette = choice;
    root.dataset.theme = themes[resolve(choice, darkMode.matches)].scheme;
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
      meta.content = themes[resolve(choice, meta.media.includes("dark"))].page;
    }
  };

  root.classList.add("js");
  apply(stored());
  darkMode.addEventListener("change", () => apply(stored()));

  return {
    stored,
    choose(choice) {
      apply(choice);
      try {
        if (choice === "system") localStorage.removeItem(key);
        else localStorage.setItem(key, choice);
      } catch {
        // Storage is blocked; the choice lasts until the page is left.
      }
    },
  };
})();
