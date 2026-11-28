import { PDFDocument, degrees } from "pdf-lib";
import { MAX_BYTES } from "./model.mjs";
import { inspectPdf } from "./pdf.mjs";
import { printGeometry } from "./paper.mjs";
import { handoutName, handoutOptions, slidesPerSide } from "./handout.mjs";

const grids = { 2: [2, 1], 4: [2, 2], 6: [2, 3], 9: [3, 3] };

async function buildHandout(file, options) {
  const source = await PDFDocument.load(await file.arrayBuffer(), { updateMetadata: false });
  const output = await PDFDocument.create({ updateMetadata: false });
  const count = slidesPerSide(options);
  const [columns, rows] = grids[count];
  const ranges = options.pageRanges?.length ? options.pageRanges : [{ start: 1, end: options.pages }];
  const selected = ranges.flatMap(({ start, end }) => Array.from({ length: end - start + 1 }, (_, i) => start + i - 1));
  const geometry = page => {
    const box = page.getCropBox();
    const rotation = ((page.getRotation().angle % 360) + 360) % 360;
    return { box, rotation, sideways: rotation === 90 || rotation === 270 };
  };
  const first = geometry(source.getPage(selected[0]));
  const firstWidth = first.sideways ? first.box.height : first.box.width;
  const firstHeight = first.sideways ? first.box.width : first.box.height;
  const { sheetW, sheetH, margin } = printGeometry(options.paper, firstWidth * columns, firstHeight * rows, false);
  const gap = Math.min(8, margin / 2);
  const cellW = (sheetW - 2 * margin - (columns - 1) * gap) / columns;
  const cellH = (sheetH - 2 * margin - (rows - 1) * gap) / rows;
  let sheet;
  for (const [index, pageIndex] of selected.entries()) {
    const slot = index % count;
    if (slot === 0) sheet = output.addPage([sheetW, sheetH]);
    const page = source.getPage(pageIndex);
    if (!page.node.Contents()) continue;
    const { box, rotation, sideways } = geometry(page);
    const scale = Math.min(cellW / (sideways ? box.height : box.width), cellH / (sideways ? box.width : box.height));
    const width = box.width * scale;
    const height = box.height * scale;
    const left = margin + (slot % columns) * (cellW + gap) + (cellW - (sideways ? height : width)) / 2;
    const bottom = sheetH - margin - (Math.floor(slot / columns) + 1) * cellH -
      Math.floor(slot / columns) * gap + (cellH - (sideways ? width : height)) / 2;
    const embedded = await output.embedPage(page, {
      left: box.x, bottom: box.y, right: box.x + box.width, top: box.y + box.height
    });
    sheet.drawPage(embedded, {
      x: left + (rotation === 180 ? width : rotation === 270 ? height : 0),
      y: bottom + (rotation === 90 ? width : rotation === 180 ? height : 0),
      width, height, rotate: degrees(-rotation)
    });
  }
  return output.save({ addDefaultPage: false, updateFieldAppearances: false });
}

export async function prepareHandout(item) {
  const count = slidesPerSide(item.options);
  if (count === 1) return item;
  let bytes;
  try {
    bytes = await buildHandout(item.file, item.options);
  } catch {
    throw new Error(`Could not arrange slides for ${item.pdf.name}. Try re-exporting the PowerPoint as a PDF.`);
  }
  if (bytes.byteLength > MAX_BYTES) {
    throw new Error(`The arranged PDF for ${item.pdf.name} exceeds 5 MB. Use fewer selected slides or a smaller PDF.`);
  }
  const file = new Blob([bytes], { type: "application/pdf" });
  file.name = handoutName(item.pdf.name, count);
  const pdf = await inspectPdf(file);
  const options = handoutOptions(item.options);
  if (pdf.pages !== options.pages) throw new Error("The handout page count changed. Review your layout and try again.");
  return { ...item, file, pdf, options, rawRanges: "" };
}
