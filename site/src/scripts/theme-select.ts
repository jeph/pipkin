// Connects a theme menu to window.pipkinTheme, which scripts/theme-init.js defines inline so the theme applies before
// the page paints.

const THEMES = ["system", "porcelain", "clay", "cobalt", "stone", "obsidian"] as const;

type Theme = (typeof THEMES)[number];

declare global {
  interface Window {
    pipkinTheme: {
      /** The saved choice; system when there is none. */
      stored(): Theme;
      /** Applies [theme] and saves it. */
      choose(theme: Theme): void;
    };
  }
}

export function connectThemeSelect(select: HTMLSelectElement): void {
  select.value = window.pipkinTheme.stored();
  select.addEventListener("change", () => {
    window.pipkinTheme.choose(THEMES.find((theme) => theme === select.value) ?? "system");
    // The guide shows the menu twice, in its header and its mobile menu, so the other copy follows.
    for (const other of document.querySelectorAll<HTMLSelectElement>(".theme-select select")) {
      other.value = select.value;
    }
  });
}
