export const standardPapers = ["A4", "Letter", "Legal"];
export const quotedPapers = ["A3", "A5", "B5", "Folio", "Tabloid"];

const dimensionPattern = "([5-9][0-9]|[1-9][0-9]{2}|1000)";
const customPattern = new RegExp(`^Custom ${dimensionPattern}x${dimensionPattern} mm$`);

export function customDimensions(paper) {
  const match = customPattern.exec(paper);
  return match ? [Number(match[1]), Number(match[2])] : null;
}

export function customPaper(width, height) {
  const dimensions = [width, height].map(String);
  if (dimensions.some(dimension => !new RegExp(`^${dimensionPattern}$`).test(dimension))) return null;
  return `Custom ${dimensions[0]}x${dimensions[1]} mm`;
}

export function validPaper(paper) {
  return typeof paper === "string" &&
    (standardPapers.includes(paper) || quotedPapers.includes(paper) || customDimensions(paper) !== null);
}

export function requiresPaperQuote(paper) {
  return !standardPapers.includes(paper);
}

export function paperLabel(paper) {
  const dimensions = customDimensions(paper);
  return dimensions ? `${dimensions[0]} × ${dimensions[1]} mm (custom)` : paper;
}

const sheetsMm = {
  A4: [210, 297], Letter: [215.9, 279.4], Legal: [215.9, 355.6],
  A3: [297, 420], A5: [148, 210], B5: [176, 250], Folio: [215.9, 330.2], Tabloid: [279.4, 431.8]
};
export const stockPapers = [...standardPapers, ...quotedPapers, "Custom"];
export const stockLabels = { available: "Available", out: "Out of stock", ask: "Ask the shop" };
export const stockKey = paper => customDimensions(paper) ? "Custom" : paper;
export function paperStock(shop, paper) {
  const key = stockKey(paper);
  if (shop?.paperStock?.out?.includes(key)) return "out";
  return shop?.paperStock?.available?.includes(key) ? "available" : "ask";
}
export const printScaleLabel = options => options.actualSize ? "Actual size" : "Fit to paper";

export function printGeometry(paper, width, height, actualSize) {
  const millimeters = customDimensions(paper) ?? sheetsMm[paper] ?? sheetsMm.A4;
  const [short, long] = millimeters.map(mm => mm * 72 / 25.4).sort((a, b) => a - b);
  const [sheetW, sheetH] = width > height ? [long, short] : [short, long];
  const margin = Math.min(5 * 72 / 25.4, sheetW / 8, sheetH / 8);
  const fit = Math.min(1, (sheetW - 2 * margin) / width, (sheetH - 2 * margin) / height);
  const scale = actualSize ? 1 : fit;
  return { sheetW, sheetH, margin, scale, clipped: !!actualSize && fit < .999,
    x: (sheetW - width * scale) / 2, y: (sheetH - height * scale) / 2 };
}

export function validatePaperStock(stock) {
  if (stock == null) return;
  const { available, out } = stock;
  if (!Array.isArray(available) || !Array.isArray(out) ||
      [...available, ...out].some(paper => !stockPapers.includes(paper)) ||
      new Set([...available, ...out]).size !== available.length + out.length) {
    throw new Error("Check the shop’s paper availability settings.");
  }
}
