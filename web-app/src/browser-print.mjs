import { abortable } from "./abort.mjs";
import { printGeometry } from "./paper.mjs";
import { renderPrintPage } from "./pdf.mjs";

export function pagesForPrint(options, count) {
  const ranges = options.pageRanges?.length ? options.pageRanges : [{ start: 1, end: count }];
  const pages = ranges.flatMap(({ start, end }) =>
    Array.from({ length: end - start + 1 }, (_, index) => start + index));
  if (!pages.length || pages.some(page => page < 1 || page > count)) {
    throw new Error("The PDF no longer matches the selected pages.");
  }
  return pages;
}

export async function prepareBrowserPrint(document, pdf, options, signal, onProgress) {
  const view = document.defaultView;
  const pages = pagesForPrint(options, pdf.numPages);
  const first = (await abortable(pdf.getPage(pages[0]), signal)).getViewport({ scale: 1 });
  const landscape = first.width > first.height;
  const geometry = printGeometry(options.paper, first.width, first.height, options.actualSize);
  const width = Number(geometry.sheetW.toFixed(3));
  const height = Number(geometry.sheetH.toFixed(3));
  const container = document.createElement("section");
  container.id = "print-job";
  const style = document.createElement("style");
  style.media = "print";
  style.textContent = `
    @page { size: ${width}pt ${height}pt; margin: 0; }
    html, body { margin: 0 !important; padding: 0 !important; background: #fff !important; }
    body > :not(#print-job) { display: none !important; }
    #print-job { display: block !important; }
    #print-job img { display: block; width: ${width}pt; height: ${height}pt; break-after: page; }
    #print-job img:last-child { break-after: auto; }
  `;
  const urls = [];
  const canvas = document.createElement("canvas");
  const endPrint = () => {
    style.remove();
    container.remove();
    view.removeEventListener("afterprint", endPrint);
  };
  const dispose = () => {
    endPrint();
    urls.forEach(url => URL.revokeObjectURL(url));
    urls.length = 0;
  };
  try {
    for (const [index, page] of pages.entries()) {
      signal.throwIfAborted();
      onProgress(index + 1, pages.length);
      await renderPrintPage(pdf, page, canvas, options, { landscape, showMargins: false, dpi: 200 / 72, signal });
      signal.throwIfAborted();
      const bytes = await abortable(new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", .95)), signal);
      if (!bytes) throw new Error("The browser could not prepare a page for printing.");
      const url = URL.createObjectURL(bytes);
      urls.push(url);
      const image = document.createElement("img");
      image.alt = `PDF page ${page}`;
      image.src = url;
      await abortable(image.decode(), signal);
      container.append(image);
    }
  } catch (error) {
    dispose();
    throw error;
  } finally {
    canvas.width = canvas.height = 0;
  }
  return {
    print() {
      document.head.append(style);
      document.body.append(container);
      view.addEventListener("afterprint", endPrint, { once: true });
      try { view.print(); }
      catch (error) { endPrint(); throw error; }
    },
    dispose
  };
}
