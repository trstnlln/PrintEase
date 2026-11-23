import { paperStock } from "./paper.mjs";
import { serializeOptions } from './model.mjs';

export const MAX_ATTACHMENTS = 2;
export function batchRangesValid(draft) {
  if (!draft.attachments?.length) return true;
  const ranges = draft.options.pageRanges ?? [];
  return !ranges.length || ranges.length === 1 && ranges[0].start === 1 && ranges[0].end === draft.options.pages;
}

export function requireBatchRanges(draft) {
  if (!batchRangesValid(draft)) throw new Error("Multi-PDF requests print all pages. Clear the page ranges or submit this PDF separately.");
}

export function attachmentEntries(attachments = []) {
  if (attachments.length > MAX_ATTACHMENTS) throw new Error("Choose up to three PDFs per request.");
  return attachments.map(({ pdf, options }) => {
    if (!pdf || !/^[a-f0-9]{64}$/.test(pdf.sha256) || options.pages !== pdf.pages ||
        (options.pageRanges?.length ?? 0)) throw new Error("Check the additional PDF and its print options.");
    const { pageRanges, selectedPages, ...allPagesOptions } = serializeOptions(options);
    return { fileName: pdf.name.slice(0, 255), fileHash: pdf.sha256, options: allPagesOptions };
  });
}

export function sameAttachments(first = [], second = []) {
  return first.length === second.length && first.every((item,i) => {
    const other = second[i];
    return item.fileName === other.fileName && item.fileHash === other.fileHash &&
      JSON.stringify(serializeOptions(item.options)) === JSON.stringify(serializeOptions(other.options));
  });
}

export async function uploadRequestFiles(uploader, user, draft, progress, orderId) {
  const files = [draft, ...(draft.attachments ?? [])];
  let primary;
  for (const [index, file] of files.entries()) {
    let stored;
    const report = fraction => progress((index + fraction) / files.length, { index, total: files.length, name: file.pdf.name, fraction });
    report(0);
    try { stored = await uploader.upload(user, file, report, orderId); }
    catch (error) {
      if (files.length === 1) throw error;
      const failure = new Error(`File ${index + 1} of ${files.length} (${file.pdf.name}) could not send. ${error.message} Retry to continue.`);
      failure.code = error.code;
      throw failure;
    }
    if (!primary) primary = stored;
  }
  return primary;
}

export function requestOutOfStock(draft, shop) {
  return [draft, ...(draft.attachments ?? [])].some(file => paperStock(shop, file.options.paper) === "out");
}
