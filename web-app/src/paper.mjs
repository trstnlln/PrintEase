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
