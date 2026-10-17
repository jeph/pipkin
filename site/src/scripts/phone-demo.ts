// Drives the hand-built chat in PhoneDemo.astro. The markup already shows the finished chat; this replays it on a loop
// while the phone is on screen, and leaves it alone for people who prefer reduced motion.

const TIMER_SECONDS_PER_TICK = 1;
const TIMER_TICK_MS = 190;
const FINAL_SECONDS = 72;

export function playDemo(phone: HTMLElement): void {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  const part = <T extends HTMLElement = HTMLElement>(name: string) =>
    phone.querySelector<T>(`[data-part="${name}"]`)!;
  const empty = part("empty");
  const sent = part("sent");
  const typed = part("typed");
  const widget = part("widget");
  const timer = part("timer");
  const notification = part("notify");
  const replyStep = part("reply");
  const reply = replyStep.querySelector<HTMLElement>("[data-reply]")!;
  const tools = [...phone.querySelectorAll<HTMLElement>('[data-part="tool"]')];
  const prompt = sent.textContent!.trim();
  const replyWords = parseReply(reply.dataset.reply!);

  let onScreen = false;
  // While the phone is off screen or the tab is hidden, every sleeper waits on the same gate.
  let gate: PromiseWithResolvers<void> | undefined;
  const wake = () => {
    if (!onScreen || document.hidden) return;
    gate?.resolve();
    gate = undefined;
  };

  // Sleeps count only while the phone is visible, so the replay pauses off screen instead of racing ahead.
  const sleep = async (ms: number) => {
    let left = ms;
    while (left > 0) {
      if (!onScreen || document.hidden) {
        gate ??= Promise.withResolvers<void>();
        await gate.promise;
      }
      const step = Math.min(left, 100);
      const { promise, resolve } = Promise.withResolvers<void>();
      setTimeout(resolve, step);
      await promise;
      left -= step;
    }
  };

  let seconds = 0;
  let ticking = false;
  const tick = async () => {
    while (ticking) {
      timer.textContent = formatElapsed(seconds);
      await sleep(TIMER_TICK_MS);
      seconds += TIMER_SECONDS_PER_TICK;
    }
  };

  const setContext = (percent: number) => phone.style.setProperty("--context", String(percent));

  const reset = () => {
    phone.classList.remove("typing", "sent", "busy");
    empty.classList.remove("off");
    widget.classList.remove("on");
    notification.classList.remove("on");
    for (const step of [sent, replyStep, ...tools]) step.classList.remove("on", "busy");
    typed.textContent = "";
    reply.replaceChildren();
    timer.textContent = "";
    seconds = 0;
    setContext(0);
  };

  const play = async () => {
    reset();
    await sleep(900);

    phone.classList.add("typing");
    for (const char of prompt) {
      typed.textContent += char;
      await sleep(char === " " ? 70 : 28 + Math.random() * 30);
    }
    await sleep(450);

    phone.classList.remove("typing");
    phone.classList.add("sent", "busy");
    typed.textContent = "";
    empty.classList.add("off");
    sent.classList.add("on");
    setContext(3);
    ticking = true;
    void tick();
    await sleep(700);

    for (const [index, tool] of tools.entries()) {
      tool.classList.add("on", "busy");
      if (index === 1) widget.classList.add("on");
      await sleep(index === 0 || tool.dataset.failed !== undefined ? 1500 : 1050);
      tool.classList.remove("busy");
      setContext(4 + index * 1.2);
      await sleep(180);
    }

    replyStep.classList.add("on");
    for (const word of replyWords) {
      reply.append(word);
      await sleep(55);
    }

    ticking = false;
    phone.classList.remove("busy");
    timer.textContent = formatElapsed(FINAL_SECONDS);
    await sleep(900);
    notification.classList.add("on");
    await sleep(3200);
    notification.classList.remove("on");
    await sleep(2600);

    phone.classList.add("fading");
    await sleep(600);
    reset();
    await sleep(80);
    phone.classList.remove("fading");
  };

  let started = false;
  const observer = new IntersectionObserver(
    ([entry]) => {
      onScreen = entry!.isIntersecting;
      wake();
      if (!onScreen || started) return;
      started = true;
      phone.classList.add("playing");
      void (async () => {
        for (;;) await play();
      })();
    },
    { threshold: 0.25 },
  );
  observer.observe(phone);
  document.addEventListener("visibilitychange", wake);
}

/** Splits `**bold**` markup into word nodes that stream in one at a time. */
function parseReply(source: string): Node[] {
  const nodes: Node[] = [];
  for (const [index, chunk] of source.split("**").entries()) {
    for (const word of chunk.split(/(?<= )/)) {
      if (!word) continue;
      if (index % 2 === 0) {
        nodes.push(document.createTextNode(word));
        continue;
      }
      const strong = document.createElement("strong");
      strong.textContent = word;
      nodes.push(strong);
    }
  }
  return nodes;
}

function formatElapsed(total: number): string {
  if (total < 60) return `0:${String(total).padStart(2, "0")}`;
  return `${Math.floor(total / 60)}m ${total % 60}s`;
}
