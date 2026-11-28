import { collection, doc, getDocFromServer, limit, onSnapshot, orderBy, query, runTransaction, serverTimestamp, where } from "firebase/firestore";
import * as demo from "./db.mjs";
import { escapeHtml as e, MAX_FILE_MB, pesos } from "./model.mjs";

export const catalog = [
  ["pvc-id", "PVC ID"], ["print-edit", "Print & edit"], ["photocopy", "Photocopy"],
  ["bookbind", "Bookbind"], ["scan", "Scan"], ["tarpapel", "Tarpapel"],
  ["school-supplies", "School supplies"], ["laminate", "Laminate"], ["rush-id", "Rush ID"],
  ["vellum-magazine", "Vellum / Magazine"], ["tarpaulin", "Tarpaulin layout & CAD"],
  ["brochure-booklet", "Brochure & Booklet"]
];
const names = new Map(catalog);
const nextStatus = { Approved: "In progress", "In progress": "Ready for pickup", "Ready for pickup": "Completed" };
const quoteStates = ["Submitted", "Quoted", "Declined"];
const isIdService = service => service === "pvc-id" || service === "rush-id";

export function serviceFields(input) {
  const { service, quantity, details, size, sides } = input;
  if (!names.has(service)) throw new Error("Choose a shop service.");
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 1000) throw new Error("Enter a quantity from 1 to 1000.");
  if (service === "tarpaulin" && !size) throw new Error("Enter the dimensions and unit.");
  return { service, quantity, details, size: service === "tarpaulin" ? size : "", sides: isIdService(service) ? sides : "" };
}

export function serviceUpdate(current, version, action, input, admin) {
  if (current?.version !== version) throw new Error("This request changed. Review the latest details and try again.");
  let change;
  if (admin && action === "quote" && quoteStates.includes(current.status)) {
    if (!Number.isInteger(input.quote) || input.quote < 1 || input.quote > 200000000 || !input.pickup.trim()) {
      throw new Error("Enter a total in whole pesos and a pickup schedule.");
    }
    change = { status: "Quoted", quote: input.quote, pickup: input.pickup.trim() };
  } else if (admin && action === "progress" && nextStatus[current.status]) {
    change = { status: nextStatus[current.status] };
  } else if (!admin && current.status === "Quoted" && ["Approved", "Declined"].includes(action)) {
    change = { status: action };
  } else {
    throw new Error("This action is unavailable. Review the current status.");
  }
  return { ...change, version: version + 1 };
}

export class ServiceDesk {
  constructor(refresh) {
    this.refresh = refresh;
    this.stop = () => {};
  }

  sync(state, backend) {
    const route = state.route;
    const kind = route === "order-messages" ? "orders" : "serviceRequests";
    const id = ["service-detail", "order-messages"].includes(route) ? state.selected : "";
    const session = state.session;
    const active = session && ["services", "service-new", "service-detail", "order-messages"].includes(route);
    const key = active ? JSON.stringify([state.mode, session.uid, session.admin, route, id]) : "";
    if (this.key === key) return;
    this.stop();
    this.key = key;
    this.backend = backend;
    this.session = session;
    this.local = state.mode === "demo";
    this.kind = kind;
    this.id = id;
    this.rows = [];
    this.messages = [];
    this.threadVerified = this.local;
    this.request = null;
    this.error = "";
    this.loading = Boolean(active);
    this.form = { service: "pvc-id", quantity: "1", details: "", size: "", sides: "Single-sided" };
    this.text = "";
    this.quoteDraft = null;
    this.file = null;
    this.preparedDesign = null;
    this.newId = active && route === "service-new" ? "service-" + crypto.randomUUID() : "";
    const handle = fn => value => {
      if (this.key !== key) return;
      fn(value);
      this.refresh();
    };
    const error = handle(err => {
      this.loading = false;
      this.error = err.code === "permission-denied"
        ? "This request is unavailable, or the shop still needs to activate service requests and messages."
        : err.message;
    });
    this.stop = () => {};
    if (!active) return;
    if (this.local) {
      this.reloadDemo = () => Promise.all([demo.all("settings"), id ? demo.get(kind === "orders" ? "orders" : "settings", id) : null]).then(handle(([rows, request]) => {
        if (id && (!request || (request.ownerId !== session.uid && !session.admin))) {
          this.error = "This request is unavailable.";
          this.loading = false;
          return;
        }
        this.rows = rows.filter(item => item.id.startsWith("service-") && item.ownerId && (session.admin || item.ownerId === session.uid)).sort(newest);
        this.messages = rows.filter(item => item.thread === kind + "/" + id).sort((a, b) => a.createdAt - b.createdAt);
        this.request = request ?? null;
        this.loading = false;
      }), error);
      this.reloadDemo();
      return;
    }
    const db = backend.db;
    if (route === "service-new") {
      getDocFromServer(doc(db, "capabilities", "service-requests-v1")).then(handle(() => { this.loading = false; }), error);
      return;
    }
    const unsub = [];
    if (!id && route === "services") {
      const requests = collection(db, "serviceRequests");
      unsub.push(onSnapshot(session.admin ? requests : query(requests, where("ownerId", "==", session.uid)), handle(snapshot => {
        this.rows = snapshot.docs.map(record).sort(newest);
        this.loading = false;
      }), error));
    } else if (id) {
      if (kind === "serviceRequests") {
        unsub.push(onSnapshot(doc(db, kind, id), handle(snapshot => {
          this.request = snapshot.exists() ? record(snapshot) : null;
          this.loading = false;
        }), error));
      }
      unsub.push(onSnapshot(query(collection(db, kind, id, "messages"), orderBy("createdAt", "desc"), limit(100)), { includeMetadataChanges: true }, handle(snapshot => {
        if (!this.threadVerified && snapshot.metadata.fromCache) return;
        this.threadVerified = true;
        this.messages = snapshot.docs.map(record).reverse();
        if (kind === "orders") this.loading = false;
      }), error));
    }
    this.stop = () => unsub.forEach(stop => stop());
  }

  prepareDesign(file) {
    if (this.preparedDesign?.file === file) return this.preparedDesign.promise;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new Error("Preparing the design timed out. Choose the file again and retry.")), 30000);
    const preview = { file, promise: null };
    preview.promise = import("./print-file.mjs")
      .then(({ preparePrintFile }) => preparePrintFile(file, { signal: controller.signal }))
      .catch(error => {
        if (this.preparedDesign === preview) this.preparedDesign = null;
        throw error;
      })
      .finally(() => clearTimeout(timer));
    this.preparedDesign = preview;
    return preview.promise;
  }

  selectDesign(file) {
    this.file = file;
    this.preparedDesign = null;
  }

  async submit() {
    const id = this.newId;
    const fields = serviceFields({ ...this.form, quantity: Number(this.form.quantity), details: this.form.details.trim(), size: this.form.size.trim() });
    let design = null;
    if (this.file) {
      const prepared = await this.prepareDesign(this.file);
      const fileName = prepared.pdf.name.slice(0, 255);
      if (this.local) await demo.put("files", { id, file: prepared.file });
      else await this.backend.documents.upload(this.backend.auth.currentUser, prepared, () => {}, id);
      design = { fileName, fileHash: prepared.pdf.sha256 };
    }
    const timestamp = this.local ? Date.now() : serverTimestamp();
    const values = {
      ...fields, ownerId: this.session.uid, customerName: (this.session.name || this.session.email || "Customer").slice(0, 200),
      status: "Submitted", quote: null, pickup: "", createdAt: timestamp, updatedAt: timestamp, version: 0, design
    };
    if (this.local) await demo.put("settings", { id, ...values });
    else {
      const ref = doc(this.backend.db, "serviceRequests", id);
      await runTransaction(this.backend.db, async tx => {
        if (!(await tx.get(ref)).exists()) tx.set(ref, values);
      });
    }
    return id;
  }

  async update(action, version, input) {
    const calc = current => serviceUpdate(current, version, action, input, this.session.admin);
    if (this.local) {
      await demo.transact(["settings"], async tx => {
        const store = tx.objectStore("settings");
        const current = await demo.request(store.get(this.id));
        await demo.request(store.put({ ...current, ...calc(current), updatedAt: Date.now() }));
      });
      await this.reloadDemo();
    } else {
      const ref = doc(this.backend.db, "serviceRequests", this.id);
      await runTransaction(this.backend.db, async tx => {
        tx.update(ref, { ...calc((await tx.get(ref)).data()), updatedAt: serverTimestamp() });
      });
    }
    this.quoteDraft = null;
  }

  async send() {
    const text = this.text.trim();
    if (!text || text.length > 1000) throw new Error("Enter a message of up to 1,000 characters.");
    const sender = { senderId: this.session.uid, senderRole: this.session.admin ? "Shop" : "Customer", text };
    if (this.local) {
      await demo.put("settings", { id: crypto.randomUUID(), ...sender, thread: this.kind + "/" + this.id, createdAt: Date.now() });
      await this.reloadDemo();
    } else {
      const ref = doc(collection(this.backend.db, this.kind, this.id, "messages"));
      await runTransaction(this.backend.db, async tx => {
        if (!(await tx.get(ref)).exists()) tx.set(ref, { ...sender, createdAt: serverTimestamp() });
      });
    }
    this.text = "";
  }

  async designBytes() {
    if (!this.local) return this.backend.documents.serviceBytes(this.backend.auth.currentUser, this.id);
    const file = (await demo.get("files", this.id))?.file;
    if (!file) throw new Error("The design file is unavailable.");
    return new Uint8Array(await file.arrayBuffer());
  }

  async download() {
    const name = this.request.design.fileName;
    const bytes = await this.designBytes();
    const url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
}
const record = doc => ({ id: doc.id, ...doc.data({ serverTimestamps: "estimate" }) });
const millis = value => value?.toMillis?.() ?? value ?? 0;
const newest = (a, b) => millis(b.updatedAt) - millis(a.updatedAt);
const date = value => millis(value) ? new Date(millis(value)).toLocaleString() : "";
const url = id => encodeURIComponent(id);
const disabled = state => state.busy || (!state.online && state.mode !== "demo") ? "disabled" : "";
const field = (id, label, html) => `<label for="${id}">${label}</label>${html}`;

function thread(s, desk, title) {
  const order = s.route === "order-messages" ? s.orders.find(order => order.id === s.selected) : null;
  const customer = (order?.studentName ?? desk.request?.customerName ?? desk.request?.studentName)?.trim()
    || (s.session.admin ? "Customer" : s.session.name?.trim() || "Customer");
  const shop = s.settings?.name?.trim() || "Nano Print Alangilan";
  return `<section class="card stack service-thread"><div><h2>${title}</h2>
    <p class="muted">Visible to the customer and authorized shop staff.</p></div>
    <ol aria-label="Recent messages" role="log" class="service-messages" tabindex="0">${desk.messages.map(m => `<li class="${m.senderId === s.session.uid ? "own" : ""}"><strong>${e(m.senderRole === "Shop" ? shop : customer)}</strong> <small>${e(date(m.createdAt))}</small><p>${e(m.text)}</p></li>`).join("")}</ol>
    ${!desk.threadVerified ? '<p class="muted">Connecting to messages…</p>' : !desk.messages.length ? '<p class="muted">No messages yet.</p>' : ""}
    <form class="stack" id="service-message-form">
      ${field("service-message", "Message", `<textarea id="service-message" rows="2" maxlength="1000" required ${desk.threadVerified ? disabled(s) : "disabled"}>${e(desk.text)}</textarea>`)}
      <button class="button" ${desk.threadVerified ? disabled(s) : "disabled"}>Send message</button>
    </form></section>`;
}

export function serviceView(s, desk) {
  const title = s.route === "service-new" ? "Request a service" : s.route === "order-messages" ? "Order messages" : "Service requests";
  const back = s.route === "order-messages" ? "#detail/" + url(s.selected) : s.route === "services" ? "#home" : "#services";
  const head = `<div class="page-title"><div><a class="text-action" href="${back}">Back</a><h1>${title}</h1></div>
    ${s.route === "services" && !s.session.admin ? '<a class="button" href="#service-new">Request a service</a>' : ""}</div>`;
  if (desk.error) return head + `<p class="notice" role="alert">${e(desk.error)}</p>`;
  if (desk.loading) return head + '<p class="notice" role="status">Loading…</p>';
  if (s.route === "order-messages") return head + thread(s, desk, s.session.admin ? "Message customer" : "Message shop");
  if (s.route === "services") {
    return head + `<div class="stack">${desk.rows.length ? desk.rows.map(r =>
      `<a class="card service-row" href="#service-detail/${url(r.id)}"><div><strong>${e(names.get(r.service))}</strong><p class="muted">${e(s.session.admin ? r.customerName : date(r.createdAt))}</p></div><span class="badge">${e(r.status)}</span></a>`
    ).join("") : '<section class="card empty"><h2>No service requests yet</h2><p>Requests and shop quotes appear here.</p></section>'}</div>`;
  }
  if (s.route === "service-new" && !s.session.admin) {
    const f = desk.form;
    if (s.settings?.acceptingOrders === false) return head + '<p class="notice">Online requests are paused. Try again later.</p>';
    return head + `<form id="service-request-form" class="card stack service-form">
      <p class="muted">Nano Print Alangilan will confirm availability, total price, and pickup before you approve.</p>
      ${field("service-choice", "Service", `<select id="service-choice">${catalog.map(([id, label]) => `<option value="${id}"${id === f.service ? " selected" : ""}>${e(label)}</option>`).join("")}</select>`)}
      ${field("service-quantity", "Quantity", `<input id="service-quantity" type="number" min="1" max="1000" required value="${e(f.quantity)}">`)}
      ${f.service === "tarpaulin" ? field("service-size", "Dimensions and unit", `<input id="service-size" maxlength="120" required placeholder="e.g. 3 × 4 ft" value="${e(f.size)}">`) : ""}
      ${isIdService(f.service) ? field("service-sides", "Print sides", `<select id="service-sides">${["Single-sided","Double-sided"].map(side => `<option${side === f.sides ? " selected" : ""}>${side}</option>`).join("")}</select>`) : ""}
      ${field("service-details", "Instructions (optional)", `<textarea id="service-details" rows="3" maxlength="1000" placeholder="${["photocopy","scan"].includes(f.service) ? "Number of pages and when you can bring the original" : "Materials, color, layout, or other preferences"}">${e(f.details)}</textarea>`)}
      <fieldset class="service-attachment">
        <legend>Design or reference (optional)</legend>
        <p id="service-file-summary" class="muted text-wrap">${desk.file ? `Selected file · ${e(desk.file.name)}` : `One PDF, JPG, or PNG up to ${MAX_FILE_MB} MB.`}</p>
        <div class="file-picker-actions">
          <button type="button" id="service-file-picker" class="button outline" data-action="service-pick-file"
            aria-describedby="service-file-summary" ${s.busy ? "disabled" : ""}>${desk.file ? "Replace design file" : "Choose design file"}</button>
          <input id="service-file" type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" hidden>
          ${desk.file ? `<button class="button outline" type="button" data-action="service-preview-draft" ${s.busy ? "disabled" : ""}>Preview design</button>
          <button class="button outline" type="button" data-action="service-remove-file" ${s.busy ? "disabled" : ""}>Remove attachment</button>` : ""}
        </div>
      </fieldset>
      <button class="button" ${disabled(s)}>${s.busy ? "Sending…" : "Send request"}</button>
    </form>`;
  }
  const r = desk.request;
  if (!r) return head + '<p class="notice">Request unavailable.</p>';
  const staff = s.session.admin;
  const quote = desk.quoteDraft ?? { quote: r.quote ?? "", pickup: r.pickup };
  const change = (label, action, primary = false) => `<button class="button ${primary ? "" : "outline"}" data-action="service-update" data-next="${action}" data-version="${r.version}" ${disabled(s)}>${label}</button>`;
  return head + `<div class="stack">
    <section class="card stack service-form"><div class="service-row"><h2>${e(names.get(r.service))}</h2><span class="badge">${e(r.status)}</span></div>
      ${staff ? `<p><strong>Customer</strong> ${e(r.customerName)}</p>` : ""}
      <p><strong>Quantity</strong> ${r.quantity}</p>
      ${r.sides ? `<p><strong>Print sides</strong> ${e(r.sides)}</p>` : ""}
      ${r.size ? `<p><strong>Dimensions</strong> ${e(r.size)}</p>` : ""}
      ${r.details ? `<p class="service-instructions">${e(r.details)}</p>` : ""}
      ${r.design ? `<p class="text-wrap"><strong>Design file</strong> ${e(r.design.fileName)}</p>
        <div class="file-picker-actions service-design-actions">
          <button class="button outline" data-action="service-preview" ${disabled(s)}>Preview design</button>
          <button class="button outline" data-action="service-download" ${disabled(s)}>Download PDF</button>
        </div>` : ""}
      ${r.quote ? `<h3>Shop quote · ${pesos(r.quote)}</h3><p class="service-instructions">${e(r.pickup)}</p>` : '<p class="muted">Waiting for the shop to confirm the price and pickup.</p>'}
      ${!staff && r.status === "Quoted" ? `<div class="actions">${change("Approve quote", "Approved", true)}${change("Decline quote", "Declined")}</div>` : ""}
      ${staff && nextStatus[r.status] ? change("Mark " + nextStatus[r.status].toLowerCase(), "progress", true) : ""}
    </section>
    ${staff && quoteStates.includes(r.status) ? `<form id="service-quote-form" class="card stack service-form" data-version="${r.version}"><h2>${r.status === "Quoted" ? "Update quote" : "Send a quote"}</h2>
      ${field("service-quote", "Total (₱)", `<input id="service-quote" type="number" min="1" max="200000000" required value="${e(quote.quote)}">`)}
      ${field("service-pickup", "Pickup schedule and instructions", `<textarea id="service-pickup" maxlength="250" rows="2" required>${e(quote.pickup)}</textarea>`)}
      <button class="button" ${disabled(s)}>Send quote</button></form>` : ""}
    ${thread(s, desk, staff ? "Message customer" : "Message shop")}
  </div>`;
}
