import { attachmentEntries, sameAttachments, requireBatchRanges } from "./attachment.mjs";
import { requiresPaperQuote } from "./paper.mjs";
import { newReview, canAccept, reviewUpdate } from "./review.mjs";
import * as db from "./db.mjs";
import { canTransition, dayKey, makeOrder, defaultShop, validateShop, sameOptions, validPickupTime } from "./model.mjs";
class DemoBackend {
  constructor(change) {
    this.change = change;
    this.mode = "demo";
    this.session = null;
    this.channel = typeof BroadcastChannel !== "undefined" ? new BroadcastChannel("printease-demo-v1") : null;
    if (this.channel) this.channel.onmessage = () => this.refresh();
  }
  async init() {
    await db.transact(["settings", "orders"], async (tx) => {
      const settings = tx.objectStore("settings");
      if (!await db.request(settings.get("demo-seeded"))) {
        const orders = tx.objectStore("orders");
        const now = Date.now();
        for (const o of [{ id: "sample-1042", fileName: "Research proposal.pdf", options: { paper: "A4", pages: 12, copies: 2, color: false, duplex: true }, estimatedCost: 48, status: "Printing", queue: 1, queueDay: dayKey(), createdAt: now - 15e5 }, { id: "sample-1043", fileName: "Laboratory activity.pdf", options: { paper: "A4", pages: 4, copies: 1, color: true, duplex: false }, estimatedCost: 40, status: "Pending", queue: null, queueDay: null, createdAt: now - 5e5 }]) orders.put({ ...o, ownerId: "demo-student", studentName: "Alex Student", storagePath: "", note: "" });
        settings.put({ id: "demo-seeded", value: true });
      }
    });
    const role = (await db.get("settings", "demo-role"))?.value;
    if (role) return this.login(role === "admin");
    await this.refresh();
  }
  async login(admin) {
    this.session = admin ? { uid: "demo-admin", name: "Nano Print Alangilan", email: "admin@demo.local", admin: true } : { uid: "demo-student", name: "Alex Student", email: "student@demo.local", admin: false };
    await db.put("settings", { id: "demo-role", value: admin ? "admin" : "student" });
    await this.refresh();
  }
  async refresh() {
    const orders = await db.all("orders");
    this.settings = validateShop((await db.get("settings", "shop-details"))?.value || defaultShop());
    this.change({ session: this.session, settings: this.settings, settingsError: null, orders: orders.filter((o) => this.session && (this.session.admin || o.ownerId === this.session.uid)), cached: false, syncError: null, reviewAvailable: true, paperSizesAvailable: true, ready: true });
  }
  async signOut() {
    this.session = null;
    await db.remove("settings", "demo-role");
    await this.refresh();
  }
  async submit(draft, onProgress) {
    if (!this.session) throw new Error("Sign in first.");
    requireBatchRanges(draft);
    const attachments = attachmentEntries(draft.attachments);
    await db.transact(["settings", "orders", "files"], async (tx) => {
      const store = tx.objectStore("orders"), prior = await db.request(store.get(draft.id));
      if (prior) {
        if (["Cancelled", "Rejected"].includes(prior.status) || !sameOptions(prior.options, draft.options) || !sameAttachments(prior.attachments, attachments)) throw new Error("This request was already submitted or closed. Check Orders before creating a new request.");
        return;
      }
      const shop = (await db.request(tx.objectStore("settings").get("shop-details")))?.value || defaultShop();
      if (!shop.acceptingOrders) throw new Error("The shop has paused new requests. Your draft is saved.");
      if (shop.version !== this.settings.version) throw new Error("Shop details changed. Review your updated total and try again.");
      const order = makeOrder(draft.id, this.session, draft.pdf, draft.options, shop.rates);
      if (draft.addons != null || requiresPaperQuote(draft.options.paper) || attachments.length) order.review = newReview(draft.addons ?? []);
      if (attachments.length) order.attachments = attachments;
      if (requiresPaperQuote(draft.options.paper) || attachments.length) order.estimatedCost = 0;
      store.put(order);
      tx.objectStore("files").put({ id: order.id, blob: draft.file, attachments: draft.attachments?.map(item=>item.file) ?? [] });
    });
    onProgress(1);
    await this.refresh();
    this.channel?.postMessage("update");
  }
  async transition(id, next, note = "") {
    await db.transact(["orders", "settings"], async (tx) => {
      const orders = tx.objectStore("orders"), old = await db.request(orders.get(id));
      if (!old || !canTransition(old.status, next)) throw new Error("The order changed. Review its latest status.");
      if (!this.session?.admin && !(old.ownerId === this.session?.uid && next === "Cancelled")) throw new Error("You cannot update this order.");
      if (next === "Rejected" && !note.trim()) throw new Error("Please give the student a reason.");
      if (next === "Accepted") {
        if (!canAccept(old)) throw new Error("The student must approve the final price first.");
        const day = dayKey(), settings = tx.objectStore("settings"), key = "queue-" + day;
        const existing = await db.request(settings.get(key));
        const seeded = (await db.request(orders.getAll())).filter((o) => o.queueDay === day).reduce((max, o) => Math.max(max, o.queue ?? 0), 0);
        const number = Math.max(existing?.value ?? 0, seeded) + 1;
        old.queue = number;
        old.queueDay = day;
        settings.put({ id: key, value: number });
      }
      orders.put({ ...old, updatedAt: Date.now(), completedAt: next === "Completed" ? Date.now() : old.completedAt ?? null, status: next, note: note.trim().slice(0, 500) });
    });
    await this.refresh();
    this.channel?.postMessage("update");
  }
  async reviewOrder(id, action, version, input = {}) {
    await db.transact(["orders", "files", "settings"], async tx => {
      const orders = tx.objectStore("orders");
      const order = await db.request(orders.get(id));
      const review = reviewUpdate(order, this.session, action, version, input);
      if (action === "revise") {
        const draft = input.draft;
        const shop = (await db.request(tx.objectStore("settings").get("shop-details")))?.value || defaultShop();
        if (shop.version !== this.settings.version) throw new Error("Shop rates changed. Review the new estimate.");
        const { fileName, options, pricing, estimatedCost } = makeOrder(id, this.session, draft.pdf, draft.options, shop.rates);
        requireBatchRanges(draft);
        const attachments = attachmentEntries(draft.attachments);
        Object.assign(order, { fileName, options, pricing, attachments,
          estimatedCost: requiresPaperQuote(options.paper) || attachments.length ? 0 : estimatedCost });
        tx.objectStore("files").put({ id, blob: draft.file, attachments: draft.attachments?.map(item=>item.file) ?? [] });
      }
      orders.put({ ...order, review, updatedAt: Date.now() });
    });
    await this.refresh();
    this.channel?.postMessage("update");
  }
  async setPickupTime(id, time) {
    if (!this.session?.admin) throw new Error("Only the shop can set a pickup estimate.");
    if (!validPickupTime(time)) throw new Error("Choose a pickup time within the next seven days.");
    await db.transact(["orders"], async (tx) => {
      const store = tx.objectStore("orders"), order = await db.request(store.get(id));
      if (!order || !["Pending", "Accepted", "Printing"].includes(order.status)) throw new Error("This request no longer needs a pickup estimate.");
      store.put({ ...order, expectedReadyAt: time, updatedAt: Date.now() });
    });
    await this.refresh();
    this.channel?.postMessage("changed");
  }
  async saveSettings(settings) {
    if (!this.session?.admin) throw new Error("Administrator access is required.");
    validateShop(settings);
    await db.transact(["settings"], async (tx) => {
      const store = tx.objectStore("settings"), old = (await db.request(store.get("shop-details")))?.value || defaultShop();
      if (settings.version !== old.version) throw new Error("Shop details changed. Refresh and try again.");
      store.put({ id: "shop-details", value: { ...settings, version: old.version + 1 } });
    });
    await this.refresh();
    this.channel?.postMessage("update");
  }
  async bytes(order, index=0) {
    const saved = await db.get("files", order.id);
    if (index) {
      const file = saved?.attachments?.[index-1];
      if (!file) throw new Error("This PDF is unavailable.");
      return new Uint8Array(await file.arrayBuffer());
    }
    if (saved?.blob) return new Uint8Array(await saved.blob.arrayBuffer());
    if (order.id.startsWith("sample-")) return samplePDF(order.options.pages);
    throw new Error("This demo document is no longer available. Submit a new PDF.");
  }
  close() {
    this.channel?.close();
  }
}
function samplePDF(pages = 2) {
  const objects = ["<< /Type /Catalog /Pages 2 0 R >>", ""];
  const kids = [];
  for (let i = 0; i < pages; i++) {
    const id = objects.length + 1;
    kids.push(`${id} 0 R`);
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> /Contents ${id + 1} 0 R >>`);
    const content = `BT /F1 24 Tf 40 770 Td (PrintEase - Sample document) Tj /F1 14 Tf 0 -40 Td (Page ${i + 1} of ${pages}) Tj 0 -30 Td (Upload your own PDF to try the full flow.) Tj ET`;
    objects.push(`<< /Length ${content.length} >>
stream
${content}
endstream`);
  }
  objects[1] = `<< /Type /Pages /Kids [${kids.join(" ")}] /Count ${pages} >>`;
  let text = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((o, i) => {
    offsets.push(text.length);
    text += `${i + 1} 0 obj
${o}
endobj
`;
  });
  const pos = text.length;
  text += `xref
0 ${objects.length + 1}
0000000000 65535 f${String.fromCharCode(32)}
` + offsets.slice(1).map((n) => String(n).padStart(10, "0") + " 00000 n \n").join("") + `trailer
<< /Size ${objects.length + 1} /Root 1 0 R >>
startxref
${pos}
%%EOF
`;
  return new TextEncoder().encode(text);
}
export {
  DemoBackend,
  samplePDF
};
