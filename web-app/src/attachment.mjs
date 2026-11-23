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
    const stored = await uploader.upload(user, file, part => progress((index + part) / files.length), orderId);
    if (!primary) primary = stored;
  }
  return primary;
}
