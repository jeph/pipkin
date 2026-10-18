// Connects the nav's theme menu to window.pipkinTheme, which Base.astro defines inline so the theme applies before the
// page paints.

type Theme = "auto" | "dark" | "light";

declare global {
  interface Window {
    pipkinTheme: {
      /** The saved choice; auto when there is none. */
      stored(): Theme;
      /** Applies [theme] and saves it. */
      choose(theme: Theme): void;
    };
  }
}

const parseTheme = (value: string): Theme => (value === "dark" || value === "light" ? value : "auto");

export function connectThemeSelect(select: HTMLSelectElement): void {
  select.value = window.pipkinTheme.stored();
  select.addEventListener("change", () => window.pipkinTheme.choose(parseTheme(select.value)));
}
