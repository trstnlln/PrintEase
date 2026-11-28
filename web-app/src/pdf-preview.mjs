// Keep the scrollable page list intact while rendering only the sheets near the viewport.
export function mountPreviewPages(area, { pages, initialPage, renderPage, onPage }) {
  const document = area.ownerDocument;
  const fragment = document.createDocumentFragment();
  const sheets = Array.from({ length: pages }, (_, index) => {
    const sheet = document.createElement("div");
    sheet.className = "preview-sheet";
    sheet.dataset.page = index + 1;
    fragment.append(sheet);
    return sheet;
  });
  area.replaceChildren(fragment);

  const loaded = new Map();
  let stopped = false;
  let rendering = false;
  let current = initialPage - 1;

  function pageAt(y) {
    let low = 0;
    let high = pages - 1;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      if (sheets[middle].offsetTop > y) high = middle - 1;
      else low = middle;
    }
    return low;
  }

  async function paint() {
    if (stopped || rendering) return;
    // At most one canvas is rendered at a time, even when the user scrolls quickly.
    const first = pageAt(area.scrollTop);
    const last = pageAt(area.scrollTop + area.clientHeight);
    const wanted = new Set([current]);
    for (let index = Math.max(0, first - 1); index <= Math.min(pages - 1, last + 1); index++) {
      wanted.add(index);
    }
    for (const [index, result] of loaded) {
      if (wanted.has(index)) continue;
      if (result.canvas) result.canvas.width = result.canvas.height = 0;
      sheets[index].replaceChildren();
      loaded.delete(index);
    }
    const index = [...wanted].find(index => !loaded.has(index));
    if (index === undefined) return;

    rendering = true;
    const canvas = document.createElement("canvas");
    canvas.setAttribute("aria-label", `Page ${index + 1} of ${pages}`);
    try {
      const clipped = await renderPage(index + 1, canvas);
      if (stopped) return;
      const sheet = sheets[index];
      sheet.style.aspectRatio = `${canvas.width} / ${canvas.height}`;
      sheet.replaceChildren(canvas);
      loaded.set(index, { canvas, clipped });
    } catch {
      if (stopped) return;
      const message = document.createElement("p");
      message.className = "preview-page-error";
      message.textContent = `Page ${index + 1} could not be displayed. You can still download the PDF.`;
      sheets[index].replaceChildren(message);
      loaded.set(index, {});
    } finally {
      rendering = false;
    }
    if (!stopped) {
      onPage(current + 1, loaded.get(current)?.clipped);
      void paint();
    }
  }

  function update(index) {
    current = index;
    onPage(current + 1, loaded.get(current)?.clipped);
    void paint();
  }

  function trackScroll() {
    const bottom = area.scrollHeight - area.clientHeight;
    const atEnd = bottom > 0 && area.scrollTop >= bottom - 1;
    const index = atEnd ? pages - 1 : pageAt(area.scrollTop + Math.min(area.clientHeight / 3, 120));
    if (index !== current) update(index);
    else void paint();
  }

  function jump(page) {
    const index = Math.max(0, Math.min(pages - 1, page - 1));
    area.scrollTop = Math.max(0, sheets[index].offsetTop - sheets[0].offsetTop);
    update(index);
  }

  area.addEventListener("scroll", trackScroll, { passive: true });
  const ResizeObserver = document.defaultView?.ResizeObserver;
  const observer = ResizeObserver ? new ResizeObserver(() => void paint()) : null;
  observer?.observe(area);
  jump(initialPage);
  return {
    jump,
    dispose() {
      stopped = true;
      observer?.disconnect();
      area.removeEventListener("scroll", trackScroll);
      for (const { canvas } of loaded.values()) {
        if (canvas) canvas.width = canvas.height = 0;
      }
      loaded.clear();
    }
  };
}
