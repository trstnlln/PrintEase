import { collection, doc, getDocFromServer, limit, onSnapshot, orderBy, query, runTransaction, serverTimestamp, setDoc, where } from "firebase/firestore";
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
const cancelStates = ["Submitted", "Quoted", "Declined"];
const quoteStates = [...cancelStates, "Approved"];
const manilaDate = new Intl.DateTimeFormat("en-PH", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Manila" });
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
    const quoteDetails = input.quoteDetails;
    if (quoteDetails && (!Number.isSafeInteger(quoteDetails.serviceFee) || quoteDetails.serviceFee <= 0 ||
        !Number.isSafeInteger(quoteDetails.extrasFee) || quoteDetails.extrasFee < 0 ||
        quoteDetails.serviceFee + quoteDetails.extrasFee !== input.quote ||
        !Number.isSafeInteger(quoteDetails.pickupAt) || quoteDetails.pickupAt <= Date.now() ||
        quoteDetails.pickupAt > Date.now() + 180 * 86400000)) {
      throw new Error("Enter a valid price breakdown and a pickup time within the next 180 days.");
    }
    change = { status: "Quoted", quote: input.quote, pickup: input.pickup.trim(), ...(quoteDetails ? { quoteDetails } : {}) };
  } else if (admin && action === "progress" && nextStatus[current.status]) {
    change = { status: nextStatus[current.status] };
  } else if (!admin && cancelStates.includes(current.status) && action === "Cancelled") {
    change = { status: "Cancelled" };
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

  syncAccount(state, backend) {
    const session = state.session;
    const key = session ? [state.mode, session.uid, session.admin, state.online].join("/") : "";
    if (this.accountKey === key) return;
    this.stopAccount?.();
    this.accountKey = key;
    this.enhanced = state.mode === "demo";
    if (session && state.mode !== "demo" && !state.online) {
      try { this.enhanced = localStorage.getItem("service-desk-v2") === "on"; } catch { /* Storage may be unavailable. */ }
    }
    this.activity = [];
    this.priorityRows = [];
    this.reads = new Map();
    const stops = [];
    this.stopAccount = () => stops.forEach(stop => stop());
    if (!session) return;
    const apply = fn => value => {
      if (key !== this.accountKey) return;
      fn(value);
      this.refresh();
    };
    if (state.mode === "demo") {
      this.reloadActivity = () => demo.all("settings").then(apply(rows => {
        this.activity = rows.filter(row => row.activity && (session.admin || row.ownerId === session.uid)).map(row => ({ ...row, id: row.requestId }));
        this.priorityRows = rows.filter(row => row.id.startsWith("service-") && row.ownerId);
        this.reads = new Map(rows.filter(row => row.seenBy === session.uid).map(row => [row.threadKey, row.seenAt]));
        this.markRead();
      }), () => {});
      this.reloadActivity();
      return;
    }
    getDocFromServer(doc(backend.db, "capabilities", "service-desk-v2")).then(apply(() => {
      this.enhanced = true;
      try { localStorage.setItem("service-desk-v2", "on"); } catch { /* UI stays active without persistence. */ }
      if (session.admin) {
        const priorities = query(collection(backend.db, "serviceRequests"), where("status", "in", ["Submitted","Declined","Ready for pickup"]), limit(100));
        stops.push(onSnapshot(priorities, apply(snap => { this.priorityRows = snap.docs.map(record).sort(newest); }), () => {}));
      }
      const activity = collection(backend.db, "threadActivity");
      stops.push(onSnapshot(session.admin ? activity : query(activity, where("ownerId", "==", session.uid)), apply(snap => {
        this.activity = snap.docs.map(record);
        this.markRead();
      }), () => {}));
      stops.push(onSnapshot(collection(backend.db, "users", session.uid, "threadReads"), apply(snap => {
        for (const item of snap.docs.map(record)) {
          const saved = millis(item.seenAt);
          if (saved > (this.reads.get(item.id) || 0)) this.reads.set(item.id, saved);
        }
      }), () => {}));
    }), () => {});
  }

  get unread() {
    const field = this.session?.admin ? "customerAt" : "shopAt";
    return (this.activity || []).filter(row => millis(row[field]) > (this.reads.get(threadKey(row.kind, row.id)) || 0))
      .sort((a,b) => millis(b[field]) - millis(a[field]));
  }

  markRead() {
    if (!this.enhanced || !this.id || !this.threadVerified || document.visibilityState === "hidden") return;
    const unread = this.unread.find(item => item.kind === this.kind && item.id === this.id);
    if (!unread) return;
    const seen = millis(unread[this.session.admin ? "customerAt" : "shopAt"]);
    const id = threadKey(unread.kind, unread.id);
    const previous = this.reads.get(id) || 0;
    this.reads.set(id, seen);
    const uid = this.session.uid;
    const save = this.local
      ? demo.put("settings", { id: "seen/" + uid + "/" + id, seenBy: uid, threadKey: id, seenAt: Date.now() })
      : setDoc(doc(this.backend.db, "users", uid, "threadReads", id), { seenAt: serverTimestamp() });
    save.catch(() => {
      if (this.session?.uid === uid && this.reads.get(id) === seen) this.reads.set(id, previous);
    });
  }

  async runRetention(settings) {
    if (!this.enhanced || this.local || !this.session?.admin || ![7,14,30].includes(settings?.retentionDays) || this.cleaning) return;
    const user = this.backend.auth.currentUser;
    const documents = this.backend.documents;
    if (!user || user.uid !== this.session.uid) return;
    this.cleaning = true;
    try {
      for (const kind of ["orders","serviceRequests"]) {
        let after = null;
        do {
          if (this.backend.auth.currentUser?.uid !== user.uid || !this.session?.admin) return;
          const result = await documents.sweepRetention(user, kind, after);
          after = result.next;
        } while (after);
      }
    } catch { /* After a connection failure the next pass retries the same records. */ }
    finally { this.cleaning = false; }
  }

  draftKey() { return "service-draft/" + this.local + "/" + this.session.uid; }

  async restoreDraft() {
    const draft = await demo.get("settings", this.draftKey()).catch(() => null);
    if (!draft) return null;
    const file = item => item ? Object.assign(new Blob([item.bytes], { type: item.bytes.type }), { name: item.name }) : null;
    return { form: draft.form, file: file(draft.front), backFile: file(draft.back), newId: draft.requestId };
  }

  async saveDraft() {
    if (!this.newId || !this.session) return;
    const save = file => file ? { bytes: file, name: file.name } : null;
    const key = this.key;
    const values = { id: this.draftKey(), requestId: this.newId, form: { ...this.form }, front: save(this.file), back: save(this.backFile) };
    const failed = await demo.put("settings", values).then(() => false, () => true);
    if (this.key !== key || this.draftFailure === failed) return;
    this.draftFailure = failed;
    this.refresh();
    if (failed) throw new Error("Draft could not be saved.");
  }

  async discardDraft() {
    await demo.remove("settings", this.draftKey());
    this.form = { ...defaultForm };
    this.file = this.backFile = null;
    this.newId = "service-" + crypto.randomUUID();
    this.draftFailure = false;
  }

  sync(state, backend) {
    const route = state.route;
    const kind = route === "order-messages" ? "orders" : "serviceRequests";
    const id = ["service-detail", "order-messages"].includes(route) ? state.selected : "";
    const session = state.session;
    const active = session && ["services", "service-new", "service-detail", "order-messages"].includes(route);
    const key = session ? JSON.stringify([state.mode, session.uid, session.admin, active ? route : "", id]) : "";
    if (this.key === key) return;
    this.stop();
    this.key = key;
    this.backend = backend;
    this.session = session;
    this.local = state.mode === "demo";
    this.kind = kind;
    this.id = active ? id : "";
    this.rows = [];
    this.messages = [];
    this.threadVerified = this.local;
    this.request = null;
    this.error = "";
    this.loading = Boolean(active);
    this.form = { ...defaultForm };
    this.text = "";
    this.quoteDraft = null;
    this.draftFailure = false;
    this.file = null;
    this.backFile = null;
    this.preparedDesign = new WeakMap();
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
    if (route === "service-new") {
      const draft = this.restoreDraft();
      const ready = this.local || !state.online ? Promise.resolve()
        : getDocFromServer(doc(backend.db, "capabilities", "service-requests-v1"));
      Promise.all([draft, ready]).then(handle(([saved]) => {
        if (saved?.newId) Object.assign(this, saved);
        this.loading = false;
      }), error);
      return;
    }
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
        this.markRead();
      }), error);
      this.reloadDemo();
      return;
    }
    const db = backend.db;
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
        this.markRead();
      }), error));
    }
    this.stop = () => unsub.forEach(stop => stop());
  }

  prepareDesign(file) {
    if (this.preparedDesign.has(file)) return this.preparedDesign.get(file);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new Error("Preparing the design timed out. Choose the file again and retry.")), 30000);
    const prepared = import("./print-file.mjs")
      .then(({ preparePrintFile }) => preparePrintFile(file, { signal: controller.signal }))
      .catch(error => {
        this.preparedDesign.delete(file);
        throw error;
      })
      .finally(() => clearTimeout(timer));
    this.preparedDesign.set(file, prepared);
    return prepared;
  }

  selectDesign(file, side = "front") {
    if (side === "back") this.backFile = file;
    else this.file = file;
  }

  async submit() {
    const id = this.newId;
    const session = this.session;
    const draftKey = this.draftKey();
    const local = this.local;
    const backend = this.backend;
    const ref = local ? null : doc(backend.db, "serviceRequests", id);
    const front = this.file;
    const back = this.backFile;
    const enhanced = this.enhanced;
    const fields = serviceFields({ ...this.form, quantity: Number(this.form.quantity), details: this.form.details.trim(), size: this.form.size.trim() });
    const existing = ref ? (await getDocFromServer(ref)).exists() : Boolean(await demo.get("settings", id));
    if (existing) {
      await demo.remove("settings", draftKey).catch(() => {});
      return id;
    }
    const upload = async (file, side) => {
      if (!file) return null;
      const prepared = await this.prepareDesign(file);
      const fileName = prepared.pdf.name.slice(0, 255);
      if (local) await demo.put("files", { id: designKey(id, side), file: prepared.file });
      else {
        const user = backend.auth.currentUser;
        if (user?.uid !== session.uid) throw new Error("Your account changed. Sign in and open your draft again.");
        await backend.documents.upload(user, prepared, () => {}, id);
      }
      return { fileName, fileHash: prepared.pdf.sha256 };
    };
    const design = await upload(front, "front");
    const backAllowed = enhanced && isIdService(fields.service) && fields.sides === "Double-sided";
    const designBack = backAllowed ? await upload(back, "back") : null;
    const timestamp = local ? Date.now() : serverTimestamp();
    const values = {
      ...fields, ownerId: session.uid, customerName: (session.name || session.email || "Customer").slice(0, 200),
      status: "Submitted", quote: null, pickup: "", createdAt: timestamp, updatedAt: timestamp, version: 0, design,
      ...(designBack ? { designBack } : {})
    };
    if (local) await demo.put("settings", { id, ...values });
    else {
      if (backend.auth.currentUser?.uid !== session.uid) throw new Error("Your account changed. Sign in and open your draft again.");
      await runTransaction(backend.db, async tx => {
        if (!(await tx.get(ref)).exists()) tx.set(ref, values);
      });
    }
    await demo.remove("settings", draftKey).catch(() => {});
    return id;
  }

  activityUpdate(tx, kind, id, ownerId, field) {
    tx.set(doc(this.backend.db, "threadActivity", threadKey(kind, id)), {
      kind, id, ownerId, [field]: serverTimestamp()
    }, { merge: true });
  }

  async localActivity(kind, id, ownerId, field) {
    const key = "activity/" + threadKey(kind, id);
    await demo.transact(["settings"], async tx => {
      const store = tx.objectStore("settings");
      const row = await demo.request(store.get(key));
      await demo.request(store.put({ ...row, kind, id: key, requestId: id, ownerId, activity: true, [field]: Date.now() }));
    });
    await this.reloadActivity?.();
  }

  async update(action, version, input) {
    const calc = current => serviceUpdate(current, version, action, input, this.session.admin);
    const isAlert = status => this.enhanced && this.session.admin && ["Quoted","Ready for pickup"].includes(status);
    if (this.local) {
      await demo.transact(["settings"], async tx => {
        const store = tx.objectStore("settings");
        const current = await demo.request(store.get(this.id));
        await demo.request(store.put({ ...current, ...calc(current), updatedAt: Date.now() }));
      });
      await this.reloadDemo();
      if (isAlert(this.request.status)) await this.localActivity("serviceRequests", this.id, this.request.ownerId, "shopAt");
    } else {
      const ref = doc(this.backend.db, "serviceRequests", this.id);
      await runTransaction(this.backend.db, async tx => {
        const current = (await tx.get(ref)).data();
        const change = calc(current);
        tx.update(ref, { ...change, updatedAt: serverTimestamp() });
        if (isAlert(change.status)) this.activityUpdate(tx, "serviceRequests", this.id, current.ownerId, "shopAt");
      });
    }
    this.quoteDraft = null;
  }

  async send() {
    const text = this.text.trim();
    if (!text || text.length > 1000) throw new Error("Enter a message of up to 1,000 characters.");
    const sender = { senderId: this.session.uid, senderRole: this.session.admin ? "Shop" : "Customer", text };
    const field = this.session.admin ? "shopAt" : "customerAt";
    if (this.local) {
      await demo.put("settings", { id: crypto.randomUUID(), ...sender, thread: this.kind + "/" + this.id, createdAt: Date.now() });
      await this.localActivity(this.kind, this.id, this.request.ownerId, field);
      await this.reloadDemo();
    } else {
      const ref = doc(collection(this.backend.db, this.kind, this.id, "messages"));
      await runTransaction(this.backend.db, async tx => {
        if ((await tx.get(ref)).exists()) return;
        const parent = this.enhanced ? (await tx.get(doc(this.backend.db, this.kind, this.id))).data() : null;
        tx.set(ref, { ...sender, createdAt: serverTimestamp() });
        if (parent) this.activityUpdate(tx, this.kind, this.id, parent.ownerId, field);
      });
    }
    this.text = "";
  }

  async designBytes(side = "front") {
    if (!this.local) return this.backend.documents.serviceBytes(this.backend.auth.currentUser, this.id, side);
    const file = (await demo.get("files", designKey(this.id, side)))?.file;
    if (!file) throw new Error("The design file is unavailable.");
    return new Uint8Array(await file.arrayBuffer());
  }

  async download(side = "front") {
    const name = (side === "back" ? this.request.designBack : this.request.design).fileName;
    const bytes = await this.designBytes(side);
    const url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
}
const defaultForm = { service: "pvc-id", quantity: "1", details: "", size: "", sides: "Single-sided" };
const threadKey = (kind, id) => kind + "--" + id;
const designKey = (id, side) => side === "back" ? id + "/back" : id;
const manilaInput = value => value ? new Date(value + 8 * 3600000).toISOString().slice(0, 16) : "";

export function inboxView(s, desk) {
  if (!desk.enhanced) return "";
  const rows = desk.unread;
  if (!rows.length && !s.session.admin) return "";
  return `<section class="card stack service-inbox"><h2>${s.session.admin ? "Needs reply" : "Unread updates"} <span class="badge">${rows.length}</span></h2>
    ${rows.length ? `<div class="stack service-inbox-list">${rows.map(row => {
      const order = row.kind === "orders" ? s.orders.find(order => order.id === row.id) : null;
      const label = order ? (s.session.admin ? order.studentName : order.fileName) : row.kind === "orders" ? "Print request" : "Service request";
      return `<a class="text-action" href="#${row.kind === "orders" ? "order-messages" : "service-detail"}/${url(row.id)}">${e(label)} · ${e(row.id.slice(-6).toUpperCase())}</a>`;
    }).join("")}</div>` : '<p class="muted">No unread messages.</p>'}</section>`;
}

export function adminPriorities(s, desk) {
  if (!s.session.admin || !desk.enhanced) return "";
  const sections = [
    ["Needs quote", [...desk.priorityRows.filter(r => ["Submitted","Declined"].includes(r.status))
      .map(r => ["#service-detail/" + url(r.id), names.get(r.service), r.customerName]),
      ...s.orders.filter(r => r.status === "Pending" && ["submitted","revised","declined"].includes(r.review?.stage))
        .map(r => ["#detail/" + url(r.id), r.fileName, r.studentName])]],
    ["Ready for pickup", [...desk.priorityRows.filter(r => r.status === "Ready for pickup")
      .map(r => ["#service-detail/" + url(r.id), names.get(r.service), r.customerName]),
      ...s.orders.filter(r => r.status === "Ready for Pickup").map(r => ["#detail/" + url(r.id), r.fileName, r.studentName])]],
  ];
  return `<div class="service-workspace service-priorities">${sections.map(([title, rows]) => `<section class="card stack">
    <h2>${title} <span class="badge">${rows.length}</span></h2>
    ${rows.length ? rows.slice(0, 6).map(([href,name,customer]) => `<a class="text-action" href="${href}">${e(name)} · ${e(customer)}</a>`).join("") : '<p class="muted">No requests.</p>'}
    ${rows.length > 6 ? '<a class="text-action" href="#services">Open service requests</a>' : ""}
  </section>`).join("")}</div>`;
}

function designPicker(s, desk, side, label) {
  const file = side === "back" ? desk.backFile : desk.file;
  const suffix = side === "back" ? "-back" : "";
  return `<fieldset class="service-attachment">
    <legend>${label} (optional)</legend>
    <p id="service-file-summary${suffix}" class="muted text-wrap">${file ? `Selected file · ${e(file.name)}` : `PDF, JPG, or PNG up to ${MAX_FILE_MB} MB.`}</p>
    <div class="file-picker-actions">
      <button type="button" id="service-file-picker${suffix}" class="button outline" data-action="service-pick-file" data-side="${side}"
        aria-describedby="service-file-summary${suffix}" ${s.busy ? "disabled" : ""}>${file ? "Replace design file" : "Choose design file"}</button>
      <input id="service-file${suffix}" type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" hidden>
      ${file ? `<button class="button outline" type="button" data-action="service-preview-draft" data-side="${side}" ${s.busy ? "disabled" : ""}>Preview design</button>
        <button class="button outline" type="button" data-action="service-remove-file" data-side="${side}" ${s.busy ? "disabled" : ""}>Remove attachment</button>` : ""}
    </div>
  </fieldset>`;
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
    const row = r => `<a class="card service-row" href="#service-detail/${url(r.id)}"><div><strong>${e(names.get(r.service))}</strong><p class="muted">${e(s.session.admin ? r.customerName : date(r.createdAt))}</p></div><span class="badge">${e(r.status)}</span></a>`;
    if (!desk.rows.length) return head + '<section class="card empty"><h2>No service requests yet</h2><p>Requests and shop quotes appear here.</p></section>';
    if (!s.session.admin) return head + inboxView(s, desk) + `<div class="stack">${desk.rows.map(row).join("")}</div>`;
    const groups = [
      ["Needs quote", r => ["Submitted","Declined"].includes(r.status)],
      ["Ready for pickup", r => r.status === "Ready for pickup"],
      ["In progress", r => ["Approved","In progress"].includes(r.status)],
      ["Waiting for response", r => r.status === "Quoted"],
      ["History", r => ["Completed","Cancelled"].includes(r.status)],
    ];
    return head + inboxView(s, desk) + `<div class="service-workspace">${groups.map(([label, filter]) => {
      const rows = desk.rows.filter(filter);
      return rows.length ? `<section class="stack"><h2>${label} <span class="badge">${rows.length}</span></h2>${rows.map(row).join("")}</section>` : "";
    }).join("")}</div>`;
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
      ${designPicker(s, desk, "front", desk.enhanced && isIdService(f.service) && f.sides === "Double-sided" ? "Front design" : "Design or reference")}
      ${desk.enhanced && isIdService(f.service) && f.sides === "Double-sided" ? designPicker(s, desk, "back", "Back design") : ""}
      <p class="muted">${desk.draftFailure ? "Draft could not be saved. Keep this tab open." : "Draft changes are saved on this device."}</p>
      <button class="button outline" type="button" data-action="service-discard-draft" ${s.busy ? "disabled" : ""}>Discard draft</button>
      <button class="button" ${disabled(s)}>${s.busy ? "Sending…" : "Send request"}</button>
    </form>`;
  }
  const r = desk.request;
  if (!r) return head + '<p class="notice">Request unavailable.</p>';
  const staff = s.session.admin;
  const quote = desk.quoteDraft?.version === r.version ? desk.quoteDraft : { quote: r.quoteDetails?.serviceFee ?? r.quote ?? "", extras: r.quoteDetails?.extrasFee ?? "", pickup: r.pickup,
    pickupAt: manilaInput(r.quoteDetails?.pickupAt) };
  const change = (label, action, primary = false) => `<button class="button ${primary ? "" : "outline"}" data-action="service-update" data-next="${action}" data-version="${r.version}" ${disabled(s)}>${label}</button>`;
  return head + `<div class="stack">
    <section class="card stack service-form"><div class="service-row"><h2>${e(names.get(r.service))}</h2><span class="badge">${e(r.status)}</span></div>
      ${staff ? `<p><strong>Customer</strong> ${e(r.customerName)}</p>` : ""}
      <p><strong>Quantity</strong> ${r.quantity}</p>
      ${r.sides ? `<p><strong>Print sides</strong> ${e(r.sides)}</p>` : ""}
      ${r.size ? `<p><strong>Dimensions</strong> ${e(r.size)}</p>` : ""}
      ${r.details ? `<p class="service-instructions">${e(r.details)}</p>` : ""}
      ${r.documentDeletedAt ? '<p class="muted">The design files were removed under the shop’s retention policy.</p>' :
        [["front", r.design], ["back", r.designBack]].filter(([, design]) => design).map(([side, design]) => `<p class="text-wrap"><strong>${r.designBack ? side === "back" ? "Back design" : "Front design" : "Design file"}</strong> ${e(design.fileName)}</p>
        <div class="file-picker-actions service-design-actions">
          <button class="button outline" data-action="service-preview" data-side="${side}" ${disabled(s)}>Preview design</button>
          <button class="button outline" data-action="service-download" data-side="${side}" ${disabled(s)}>Download PDF</button>
        </div>`).join("")}
      ${r.quote ? `<h3>Shop quote · ${pesos(r.quote)}</h3>${r.quoteDetails ? `<p>Service ${pesos(r.quoteDetails.serviceFee)} · Extras ${pesos(r.quoteDetails.extrasFee)}</p>
        <p><strong>Pickup</strong> ${e(manilaDate.format(r.quoteDetails.pickupAt))} (PH time)</p>` : ""}
        <p class="service-instructions">${e(r.pickup)}</p>` : r.status === "Cancelled" ? "" : '<p class="muted">Waiting for the shop to confirm the price and pickup.</p>'}
      ${!staff && r.status === "Quoted" ? `<div class="actions">${change("Approve quote", "Approved", true)}${change("Decline quote", "Declined")}</div>` : ""}
      ${!staff && desk.enhanced && cancelStates.includes(r.status) ? change("Cancel request", "Cancelled") : ""}
      ${staff && nextStatus[r.status] ? change("Mark " + nextStatus[r.status].toLowerCase(), "progress", true) : ""}
    </section>
    ${staff && quoteStates.includes(r.status) ? `<form id="service-quote-form" class="card stack service-form" data-version="${r.version}"><h2>${r.quote ? "Update quote" : "Send a quote"}</h2>
      ${r.status === "Approved" ? '<p class="muted">An updated quote requires customer approval before work starts.</p>' : ""}
      ${field("service-quote", desk.enhanced ? "Service price (₱)" : "Total (₱)", `<input id="service-quote" type="number" min="1" max="200000000" required value="${e(quote.quote)}">`)}
      ${desk.enhanced ? `${field("service-extras", "Extras (₱)", `<input id="service-extras" type="number" min="0" max="200000000" value="${e(quote.extras)}">`)}
        <p id="service-quote-total"><strong>Total</strong> ${pesos((Number(quote.quote) || 0) + (Number(quote.extras) || 0))}</p>
        ${field("service-pickup-at", "Pickup date and time (PH time)", `<input id="service-pickup-at" type="datetime-local" required value="${e(quote.pickupAt)}">`)}` : ""}
      ${field("service-pickup", desk.enhanced ? "Pickup instructions" : "Pickup schedule and instructions", `<textarea id="service-pickup" maxlength="250" rows="2" required>${e(quote.pickup)}</textarea>`)}
      <button class="button" ${disabled(s)}>Send quote</button></form>` : ""}
    ${thread(s, desk, staff ? "Message customer" : "Message shop")}
  </div>`;
}
