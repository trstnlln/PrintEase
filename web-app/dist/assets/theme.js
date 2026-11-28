(() => {
  const root = document.documentElement;
  const system = window.matchMedia("(prefers-color-scheme: dark)");
  let preference;
  try {
    const saved = window.localStorage.getItem("pe_theme");
    if (saved === "light" || saved === "dark") preference = saved;
  } catch { /* Storage can be unavailable in restricted browser contexts. */ }

  const current = () => preference ?? (system.matches ? "dark" : "light");
  const apply = () => {
    const theme = current();
    const dark = theme === "dark";
    root.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", dark ? "#17181a" : "#f7f7f5");
    for (const button of document.querySelectorAll("button[data-theme-toggle]")) {
      button.setAttribute("aria-pressed", String(dark));
      button.title = `Switch to ${dark ? "light" : "dark"} mode`;
    }
  };

  document.addEventListener("click", (event) => {
    if (!event.target.closest?.("button[data-theme-toggle]")) return;
    preference = current() === "dark" ? "light" : "dark";
    try {
      window.localStorage.setItem("pe_theme", preference);
    } catch { /* Keep the choice in memory for this visit. */ }
    apply();
  });
  system.addEventListener("change", apply);
  apply();
})();
