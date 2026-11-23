import { MAX_BYTES } from "./model.mjs";
import { inspectPdf } from "./pdf.mjs";

export const printFileTypes = "application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png";
const encoder = new TextEncoder();

export function fileKind(bytes) {
  if (bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((byte, index) => bytes[index] === byte)) return "PNG";
  if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return "JPG";
  if (String.fromCharCode(...bytes.slice(0, 5)) === "%PDF-") return "PDF";
  return null;
}

// A single JPEG XObject on a single PDF page. Offsets are counted in bytes.
export function imagePdf(jpeg, width, height) {
  const w = (width * 72 / 150).toFixed(2), h = (height * 72 / 150).toFixed(2);
  const chunks = [], offsets = [0];
  let length = 0;
  const append = data => {
    const chunk = typeof data === "string" ? encoder.encode(data) : data;
    chunks.push(chunk);
    length += chunk.length;
  };
  const object = (id, ...parts) => {
    offsets[id] = length;
    append(id + " 0 obj\n");
    parts.forEach(append);
    append("\nendobj\n");
  };
  append("%PDF-1.4\n");
  object(1, "<< /Type /Catalog /Pages 2 0 R >>");
  object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
  object(3, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${w} ${h}] /Resources << /XObject << /Photo 4 0 R >> >> /Contents 5 0 R >>`);
  object(4, `<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`, jpeg, "\nendstream");
  const content = `q ${w} 0 0 ${h} 0 0 cm /Photo Do Q\n`;
  object(5, `<< /Length ${content.length} >>\nstream\n${content}endstream`);
  const xref = length;
  append("xref\n0 6\n0000000000 65535 f \n");
  offsets.slice(1).forEach(offset => append(String(offset).padStart(10, "0") + " 00000 n \n"));
  append(`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
  return new Blob(chunks, { type: "application/pdf" });
}

function dimensions(bytes, kind) {
  const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (kind === "PNG" && bytes.length >= 24) return [data.getUint32(16), data.getUint32(20)];
  if (kind === "JPG") {
    let i = 2;
    while (i + 4 <= bytes.length && bytes[i++] === 255) {
      while (bytes[i] === 255) i++;
      const marker = bytes[i++];
      if (marker === 218 || marker === 217 || i + 2 > bytes.length) break;
      const length = data.getUint16(i);
      if (length < 2 || i + length > bytes.length) break;
      if ([192,193,194,195,197,198,199,201,202,203,205,206,207].includes(marker) && length >= 8) {
        return [data.getUint16(i + 5), data.getUint16(i + 3)];
      }
      i += length;
    }
  }
  return [0, 0];
}

async function convertImage(file, bytes, kind) {
  const [width, height] = dimensions(bytes, kind);
  if (!width || !height || width * height > 40_000_000) throw new Error("Choose a readable image under 40 megapixels.");
  const url = URL.createObjectURL(new Blob([bytes], { type: kind === "PNG" ? "image/png" : "image/jpeg" }));
  try {
    const image = new Image();
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error("This image could not be opened. Try exporting it as JPG or PNG."));
      image.src = url;
    });
    const scale = Math.min(1, 2400 / Math.max(image.naturalWidth, image.naturalHeight));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const ctx = canvas.getContext("2d", { alpha: false });
    if (!ctx) throw new Error("Image conversion is unavailable in this browser. Save the image as a PDF and try again.");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
    const jpeg = await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", .92));
    if (!jpeg || jpeg.type !== "image/jpeg") throw new Error("This image could not be converted. Try a smaller image.");
    const pdf = imagePdf(new Uint8Array(await jpeg.arrayBuffer()), canvas.width, canvas.height);
    const name = (file.name.replace(/\.[^.]+$/, "").slice(0, 250) || "Image") + ".pdf";
    return new File([pdf], name, { type: "application/pdf" });
  } finally {
    URL.revokeObjectURL(url);
  }
}

export async function preparePrintFile(file) {
  if (!file?.size || file.size > MAX_BYTES) throw new Error("Choose a PDF, JPG, or PNG file up to 5 MB.");
  let header;
  try { header = new Uint8Array(await file.slice(0, 8).arrayBuffer()); }
  catch { throw new Error("This file could not be opened. Download it to your device and choose it again."); }
  const kind = fileKind(header);
  if (!kind) throw new Error("Choose a valid PDF, JPG, or PNG. Export Word, PowerPoint, or Excel files as PDF first.");
  const printable = kind === "PDF" ? file : await convertImage(file, new Uint8Array(await file.arrayBuffer()), kind);
  const pdf = await inspectPdf(printable);
  return { pdf: { ...pdf, ...(kind === "PDF" ? {} : { sourceKind: kind }) }, file: printable };
}
