import { selectedPages } from "./model.mjs";

export const slideLayouts = [1, 2, 4, 6, 9];

export const slidesPerSide = options =>
  slideLayouts.includes(options.slidesPerSide) ? options.slidesPerSide : 1;

export const printedSides = options => Math.ceil(selectedPages(options) / slidesPerSide(options));

export function handoutOptions(options) {
  if (slidesPerSide(options) === 1) return options;
  const { selectedPages: _selected, slidesPerSide: _layout, ...rest } = options;
  return { ...rest, pages: printedSides(options), pageRanges: [], actualSize: false };
}

export function handoutName(name, count) {
  const suffix = ` (${count} slides per side).pdf`;
  return name.replace(/\.pdf$/i, "").slice(0, 255 - suffix.length) + suffix;
}
