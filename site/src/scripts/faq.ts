// Animates the FAQ's answers open and closed, one open at a time. Without JavaScript the details elements keep
// their native behaviour, including the shared name that closes the others.

const DURATION_MS = 200;

export function animateFaq(list: HTMLElement): void {
  const items = [...list.querySelectorAll<HTMLDetailsElement>("details")];
  const easing = getComputedStyle(document.documentElement).getPropertyValue("--ease-out").trim() || "ease-out";
  const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");

  const setOpen = (item: HTMLDetailsElement, open: boolean) => {
    const answer = item.querySelector<HTMLElement>(".faq-answer");
    if (!answer) return;
    // Start from wherever a running animation has got to, so a quick second tap reverses it smoothly.
    const from = item.open ? answer.getBoundingClientRect().height : 0;
    for (const animation of answer.getAnimations()) animation.cancel();
    item.dataset.state = open ? "open" : "closed";

    if (reducedMotion.matches) {
      item.open = open;
      return;
    }

    item.open = true;
    const to = open ? answer.scrollHeight : 0;
    const animation = answer.animate(
      { height: [`${from}px`, `${to}px`], opacity: open ? [0, 1] : [1, 0] },
      { duration: DURATION_MS, easing, fill: "forwards" },
    );
    animation.onfinish = () => {
      if (!open) item.open = false;
      animation.cancel();
    };
  };

  for (const item of items) {
    // The script keeps one answer open at a time itself, so a closing answer can animate instead of vanishing.
    item.removeAttribute("name");
    item.dataset.state = item.open ? "open" : "closed";

    item.querySelector("summary")?.addEventListener("click", (event) => {
      event.preventDefault();
      const opening = item.dataset.state !== "open";
      if (opening) {
        for (const other of items) {
          if (other !== item && other.dataset.state === "open") setOpen(other, false);
        }
      }
      setOpen(item, opening);
    });

    // Find in page can open an answer by itself; keep the icon in step with it.
    item.addEventListener("toggle", () => {
      if (item.open && item.dataset.state === "closed" && !item.querySelector(".faq-answer")?.getAnimations().length) {
        item.dataset.state = "open";
      }
    });
  }
}
