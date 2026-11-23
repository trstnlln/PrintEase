import { validateSchedule } from "./pickup-schedule.mjs";
import { validPaper, validatePaperStock } from "./paper.mjs";
const MAX_BYTES = 5 * 1024 * 1024;
const statuses = ["Pending", "Accepted", "Printing", "Ready for Pickup", "Completed", "Rejected", "Cancelled"];
const terminal = ["Completed", "Rejected", "Cancelled"];
const defaultRates = () => ({ bw: 2, color: 10, legalExtra: 1 });
const defaultShop = () => ({ name: "Nano Print Alangilan", address: "Neptune St., Golden Country Homes, Alangilan, Batangas City", pickupHours: "6:00 AM\u201310:00 PM", acceptingOrders: true, rates: defaultRates(), retentionDays: 0, version: 0, contactEmail: "nanoprint.alangilan@gmail.com", facebookUrl: "", ratesConfirmed: false });
function validateShop(s) {
  validateSchedule(s.pickupSchedule);
  validatePaperStock(s.paperStock);
  s = { ...s, contactEmail: s.contactEmail ?? (["Nano Alangilan", "Nano Print Alangilan"].includes(s.name) ? "nanoprint.alangilan@gmail.com" : ""), facebookUrl: s.facebookUrl ?? "", ratesConfirmed: s.ratesConfirmed ?? false };
  if (typeof s.contactEmail !== "string" || s.contactEmail.length > 254 || s.contactEmail.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s.contactEmail.trim())) throw new Error("Enter a valid shop email address.");
  if (!validFacebookUrl(s.facebookUrl) || typeof s.ratesConfirmed !== "boolean") throw new Error("Use the shop\u2019s HTTPS Facebook page link.");
  for (const [key, min, max] of [["name", 2, 100], ["address", 2, 300], ["pickupHours", 2, 200]]) if (typeof s[key] !== "string" || s[key].trim().length < min || s[key].trim().length > max) throw new Error("Complete the shop name, address and pickup hours.");
  if (typeof s.acceptingOrders !== "boolean" || ![0, 7, 14, 30].includes(s.retentionDays) || !Number.isSafeInteger(s.version) || s.version < 0) throw new Error("Check the shop settings.");
  for (const [key, min] of [["bw", 1], ["color", 1], ["legalExtra", 0]]) if (!Number.isSafeInteger(s.rates?.[key]) || s.rates[key] < min || s.rates[key] > 1e3) throw new Error("Enter whole-peso rates from \u20B11\u2013\u20B11,000 (Legal surcharge may be \u20B10).");
  return ["Nano Alangilan", "Nano Print Alangilan"].includes(s.name) ? { ...s, name: "Nano Print Alangilan", address: s.address === "Near BSU Alangilan campus" ? "Neptune St., Golden Country Homes, Alangilan, Batangas City" : s.address, pickupHours: s.pickupHours === "Confirm pickup hours with the shop" ? "6:00 AM\u201310:00 PM" : s.pickupHours } : s;
}
const defaults = () => ({ paper: "A4", pages: 1, copies: 1, color: false, duplex: false, pageRanges: [] });
function parsePageRanges(text, pages) {
  if (!text.trim()) return [];
  const ranges = text.split(",").map((part) => {
    const match = /^\s*(\d+)\s*(?:-\s*(\d+)\s*)?$/.exec(part);
    if (!match) throw new Error("Use page numbers like 1-3, 5, 8-10, or leave blank for all pages.");
    const start = Number(match[1]), end = Number(match[2] || match[1]);
    if (!Number.isSafeInteger(start) || start < 1 || end < start || end > pages) throw new Error(`Page numbers must be between 1 and ${pages}.`);
    return { start, end };
  }).sort((a, b) => a.start - b.start);
  const merged = [];
  for (const r of ranges) {
    const prior = merged.at(-1);
    if (prior && r.start <= prior.end + 1) prior.end = Math.max(prior.end, r.end);
    else merged.push({ ...r });
  }
  if (merged.length > 5) throw new Error("Choose up to 5 separate page ranges, or leave blank for all pages.");
  return merged.length === 1 && merged[0].start === 1 && merged[0].end === pages ? [] : merged;
}
const rangeText = (ranges) => (ranges || []).map((r) => r.start === r.end ? String(r.start) : `${r.start}-${r.end}`).join(", ");
const selectedPages = (o) => o.pageRanges?.length ? o.pageRanges.reduce((n, r) => n + r.end - r.start + 1, 0) : o.pages;
const pageLabel = (o) => o.pageRanges?.length && !(o.pageRanges.length === 1 && o.pageRanges[0].start === 1 && o.pageRanges[0].end === o.pages) ? `Pages ${rangeText(o.pageRanges)}` : `All ${o.pages} pages`;
function serializeOptions(o) {
  validateOptions(o);
  return { paper: o.paper, pages: o.pages, copies: o.copies, color: o.color, duplex: o.duplex, pageRanges: o.pageRanges?.length ? o.pageRanges : [{ start: 1, end: o.pages }], selectedPages: selectedPages(o), ...(o.actualSize ? { actualSize: true } : {}) };
}
const sameOptions = (a, b) => JSON.stringify(serializeOptions(a)) === JSON.stringify(serializeOptions(b));
const orderTotal = (order) => estimate(order.options, order.pricing || defaultRates()).total;
function validateOptions(o) {
  if (o.actualSize !== undefined && typeof o.actualSize !== "boolean") throw new Error("Choose Fit to paper or Actual size.");
  if (!validPaper(o.paper) || !Number.isInteger(o.pages) || o.pages < 1 || o.pages > 1e3 || !Number.isInteger(o.copies) || o.copies < 1 || o.copies > 100 || typeof o.color !== "boolean" || typeof o.duplex !== "boolean") throw new Error("Check your printing preferences. Copies must be between 1 and 100.");
  if (o.pageRanges !== void 0) {
    if (!Array.isArray(o.pageRanges) || o.pageRanges.length > 5) throw new Error("Choose up to 5 separate page ranges, or leave blank for all pages.");
    let end = 0;
    for (const r of o.pageRanges) {
      if (!Number.isInteger(r.start) || !Number.isInteger(r.end) || r.start <= end || r.end < r.start || r.end > o.pages) throw new Error("Check your selected page ranges.");
      end = r.end;
    }
  }
  if (o.selectedPages !== void 0 && o.selectedPages !== selectedPages(o)) throw new Error("The selected page count is invalid.");
  return o;
}
function estimate(o, rates = defaultRates()) {
  validateOptions(o);
  const rate = (o.color ? rates.color : rates.bw) + (o.paper === "Legal" ? rates.legalExtra : 0);
  const pages = selectedPages(o);
  return { rate, total: pages * o.copies * rate, sheets: (o.duplex ? Math.ceil(pages / 2) : pages) * o.copies };
}
const pesos = (n) => new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" }).format(n);
const dayKey = (date = /* @__PURE__ */ new Date()) => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Manila", year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
const dateLabel = (n) => new Intl.DateTimeFormat("en-PH", { timeZone: "Asia/Manila", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }).format(n);
const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const canTransition = (from, to) => ({ Pending: ["Accepted", "Rejected", "Cancelled"], Accepted: ["Printing"], Printing: ["Ready for Pickup"], "Ready for Pickup": ["Completed"] }[from] ?? []).includes(to);
function filterOrders(orders, { filter = "All", search = "", admin = false } = {}) {
  const term = search.trim().toLocaleLowerCase();
  return orders.filter((o) => (filter === "All" || (filter === "Active" ? !terminal.includes(o.status) : filter === "History" ? terminal.includes(o.status) : o.status === filter)) && (!term || `${o.fileName} ${admin ? o.studentName : ""} ${o.queue ?? ""}`.toLocaleLowerCase().includes(term))).sort(admin ? (a, b) => Number(terminal.includes(a.status)) - Number(terminal.includes(b.status)) || (a.queueDay ?? "9999").localeCompare(b.queueDay ?? "9999") || (a.queue ?? 1e10) - (b.queue ?? 1e10) || a.createdAt - b.createdAt : (a, b) => b.createdAt - a.createdAt);
}
function makeOrder(id, session, pdf, options, rates = defaultRates()) {
  validateOptions(options);
  if (session.admin) throw new Error("Sign in as a student to submit a request.");
  const now = Date.now();
  return { id, ownerId: session.uid, studentName: session.name.slice(0, 200), fileName: pdf.name.slice(0, 255), storagePath: `documents/${session.uid}/${id}.pdf`, options: serializeOptions(options), pricing: { ...rates }, estimatedCost: estimate(options, rates).total, createdAt: now, updatedAt: now, completedAt: null, documentDeletedAt: null, status: "Pending", queue: null, queueDay: null, note: "" };
}
function validatePdf(file) {
  if (!file || file.size < 5 || file.size > MAX_BYTES) throw new Error("Choose a PDF no larger than 5 MB.");
}
function errorMessage(error) {
  const code = error?.code ?? "";
  const messages = { "auth/unauthorized-domain": "Google sign-in needs this website domain added in Firebase Authentication \u2192 Settings \u2192 Authorized domains.", "auth/popup-blocked": "Safari blocked the account window. Allow pop-ups for this website, or sign in with email and password.", "auth/popup-closed-by-user": "", "auth/cancelled-popup-request": "", "auth/operation-not-allowed": "This sign-in option is currently unavailable. Try another sign-in option or contact the shop.", "auth/configuration-not-found": "Enable Authentication and the sign-in provider in your PrintEase Firebase project.", "auth/invalid-credential": "Sign-in could not be verified. Check your details and try again.", "auth/email-already-in-use": "This email is registered. Sign in or reset your password.", "auth/account-exists-with-different-credential": "This email uses another sign-in method. Use that method to sign in first.", "auth/weak-password": "Use a password with at least 8 characters.", "auth/network-request-failed": "Check your internet connection and try again.", "permission-denied": "Access was denied. Check your account and the deployed Firebase rules.", "storage/unauthorized": "The PDF could not be accessed. Check your account and Storage rules.", "storage/unknown": "The document service could not finish. Your PDF is still selected; check your connection and try again." };
  if (Object.hasOwn(messages, code)) return messages[code];
  if (String(error?.message).includes("CONFIGURATION_NOT_FOUND")) return messages["auth/configuration-not-found"];
  return (error?.message || "Something went wrong. Please try again.").slice(0, 350);
}
const online = () => {
  if (typeof navigator !== "undefined" && !navigator.onLine) throw new Error("You\u2019re offline. Your draft is saved; reconnect to submit or update an order.");
};
function timeout(promise, ms = 25e3) {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("The connection timed out. Check Orders before retrying; this draft keeps the same request ID.")), ms);
  })]).finally(() => clearTimeout(timer));
}
function validFacebookUrl(value) {
  if (value === "") return true;
  if (typeof value !== "string" || value.length > 2048) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password && (!url.port || url.port === "443") && ["facebook.com", "www.facebook.com", "m.facebook.com", "fb.com", "www.fb.com"].includes(url.hostname);
  } catch {
    return false;
  }
}
const collectionCode = (id) => String(id).replace(/[^A-Za-z0-9]/g, "").slice(-6).toUpperCase().padStart(6, "0");
const validPickupTime = (time, now = Date.now()) => time === null || Number.isSafeInteger(time) && time >= now - 6e4 && time <= now + 7 * 864e5;
function pickupLabel(order) {
  if (order.status === "Ready for Pickup") return "Ready to collect";
  if (order.status === "Completed") return "Collected";
  if (!Number.isSafeInteger(order.expectedReadyAt)) return "Waiting for the shop\u2019s estimate";
  return "Estimated ready " + dateLabel(order.expectedReadyAt);
}
export {
  MAX_BYTES,
  canTransition,
  collectionCode,
  dateLabel,
  dayKey,
  defaultRates,
  defaultShop,
  defaults,
  errorMessage,
  escapeHtml,
  estimate,
  filterOrders,
  makeOrder,
  online,
  orderTotal,
  pageLabel,
  parsePageRanges,
  pesos,
  pickupLabel,
  rangeText,
  sameOptions,
  selectedPages,
  serializeOptions,
  statuses,
  terminal,
  timeout,
  validFacebookUrl,
  validPickupTime,
  validateOptions,
  validatePdf,
  validateShop
};
