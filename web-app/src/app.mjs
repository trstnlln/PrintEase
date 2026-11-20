import { firebaseConfig } from "./config.mjs";
import { defaults, estimate, pesos, escapeHtml as e, errorMessage, validateOptions, MAX_BYTES, defaultShop, validateShop, parsePageRanges, rangeText, selectedPages, pageLabel, collectionCode, terminal } from "./model.mjs";
import { authView, shell, dashboard, requestView, detailView, accountView, settingsView, icon } from "./view.mjs";
import * as db from "./db.mjs";
import {pushState,reconcilePushUser,enablePush,disablePush,pushRequest} from "./push.mjs";
const freshDraft = () => ({ id: crypto.randomUUID(), pdf: null, file: null, options: defaults(), rawCopies: "1", rawRanges: "", rangeError: null });
class PrintEase {
  constructor({ root = document.getElementById("app"), demo = false } = {}) {
    this.root = root;
    this.s = { mode: demo ? "demo" : readMode(), session: null, orders: [], settings: defaultShop(), settingsError: null, shopEdit: null, ready: false, cached: false, syncError: null, busy: false, progress: 0, route: "home", selected: "", filter: "All", search: "", online: navigator.onLine, auth: { register: false, name: "", email: "", password: "", visible: false }, draft: freshDraft(), requestError: null, draftWarning: null, fileReading: false, push:{supported:false,enabled:false,message:null} };
    this.modal = null;
    this.preview = null;
    this.generation = 0;
    this.imageError = (event) => {
      if (event.target?.matches?.("img.profile-photo")) event.target.remove();
    };
    document.addEventListener("error", this.imageError, true);
    this.click = (e2) => this.onClick(e2);
    this.input = (e2) => this.onInput(e2);
    this.submit = (e2) => this.onSubmit(e2);
    this.hash = () => {
      if (this.s.busy) {
        this.toast("Please wait for the current action to finish.");
        return;
      }
      this.routeFromHash();
      this.render();
    };
    this.connection = () => {
      this.s.online = navigator.onLine;
      this.render();
    };
    document.addEventListener("click", this.click);
    document.addEventListener("input", this.input);
    document.addEventListener("change", this.input);
    document.addEventListener("submit", this.submit);
    window.addEventListener("hashchange", this.hash);
    window.addEventListener("online", this.connection);
    window.addEventListener("offline", this.connection);
  }
  async start() {
    this.render();
    await this.setBackend(this.s.mode);
    this.registerWorker();
  }
  async setBackend(mode) {
    this.backend?.close();
    const generation = ++this.generation;
    this.closeModal();
    this.s = { ...this.s, mode, session: null, orders: [], settings: defaultShop(), settingsError: null, shopEdit: null, ready: false, syncError: null, route: "home", draft: freshDraft(), auth: { ...this.s.auth, password: "" } };
    try {
      localStorage.setItem("pe_mode", mode);
    } catch {
    }
    this.render();
    const change = (snapshot) => {
      if (generation !== this.generation) return;
      const old = this.s.session;
      const newlyReady = old?.uid === snapshot.session?.uid && this.s.orders.some((o) => o.status !== "Ready for Pickup" && snapshot.orders.some((n) => n.id === o.id && n.status === "Ready for Pickup"));
      this.s = { ...this.s, ...snapshot };
      if (old?.uid !== snapshot.session?.uid) {
        this.s.push={...this.s.push,syncing:true};
        reconcilePushUser(snapshot.session?.uid).then(value=>{if(generation===this.generation){this.s.push={...value,message:null};this.render();}}).catch(()=>{});
        this.closeModal();
        this.s.draft = freshDraft();
        this.s.auth.password = "";
        this.s.auth.register = false;
        if (snapshot.session) {
          const uid = snapshot.session.uid;
          this.loadDraft().then(() => {
            if (this.s.session?.uid === uid) this.render();
          });
          this.routeFromHash();
        } else this.s.route = "home";
      }
      this.render();
      if (newlyReady && !snapshot.session?.admin) this.toast("Your prints are ready. Open Orders for pickup details.");
    };
    try {
      if (mode === "demo") {
        const { DemoBackend } = await import("./demo.mjs");
        this.backend = new DemoBackend(change);
      } else {
        const { FirebaseBackend } = await import("./firebase.mjs");
        this.backend = new FirebaseBackend(firebaseConfig, change);
      }
      await this.backend.init();
    } catch (error) {
      this.s.ready = true;
      this.s.syncError = errorMessage(error);
      this.render();
    }
  }
  routeFromHash() {
    const hash = location.hash.slice(1);
    const [route, id] = hash.split("/");
    this.s.route = ["home", "orders", "account", "request", "detail", "settings"].includes(route) ? route : "home";
    if (route === "detail") {
      try {
        this.s.selected = decodeURIComponent(id || "");
      } catch {
        this.s.selected = "";
      }
    }
    if (route === "settings" && !this.s.session?.admin) this.s.route = "account";
    if (this.s.session?.admin && this.s.route === "request") this.s.route = "orders";
  }
  go(route, id = "") {
    if (this.s.busy) return;
    if (route === "settings") {
      if (!this.s.session?.admin) return;
      this.s.shopEdit = structuredClone(this.s.settings);
    }
    this.s.route = route;
    if (id) this.s.selected = id;
    history.replaceState(null, "", `#${route}${id ? "/" + encodeURIComponent(id) : ""}`);
    this.render();
    window.scrollTo?.({ top: 0, behavior: "instant" });
    document.querySelector("#main")?.focus({ preventScroll: true });
  }
  render() {
    const focused = document.activeElement, id = focused?.id;
    let selection;
    try {
      selection = [focused.selectionStart, focused.selectionEnd];
    } catch {
    }
    const s = this.s;
    const servicesWereOpen = this.root.querySelector(".shop-services[open]") !== null;
    this.root.innerHTML = s.session ? shell(s, s.route === "request" ? requestView(s) : s.route === "detail" ? detailView(s) : s.route === "account" ? accountView(s) : s.route === "settings" && s.session.admin ? settingsView(s) : dashboard(s)) : authView(s);
    const services = this.root.querySelector(".shop-services");
    if (servicesWereOpen && services) services.open = true;
    if (id) {
      const node = document.getElementById(id);
      if (node && node !== focused) {
        node.focus({ preventScroll: true });
        if (selection && typeof selection[0] === "number") try {
          node.setSelectionRange(...selection);
        } catch {
        }
      }
    }
  }
  async action(fn, scope = null) {
    if (this.s.busy) return;
    const context = scope || (!this.s.session ? "auth" : this.s.route === "request" ? "request" : null);
    this.s.busy = true;
    this.s.progress = 0;
    if (context === "auth") this.s.auth.error = null;
    if (context === "request") this.s.requestError = null;
    this.render();
    try {
      return await fn();
    } catch (error) {
      const message = errorMessage(error);
      if (message) {
        if (context === "auth") this.s.auth.error = message;
        else if (context === "request") this.s.requestError = message;
        else this.toast(message);
      }
    } finally {
      this.s.busy = false;
      this.render();
    }
  }
  toast(text) {
    const node = document.getElementById("toast");
    node.textContent = text;
    node.classList.add("show");
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => node.classList.remove("show"), 7500);
  }
  async onClick(event) {
    if (this.s.busy && event.target.closest?.('a[href^="#"]')) {
      event.preventDefault();
      return;
    }
    const node = event.target.closest?.("[data-action]");
    if (!node || node.disabled) return;
    const action = node.dataset.action;
    event.preventDefault();
    if (this.s.busy && !["close-modal"].includes(action)) return;
    switch (action) {
      case "push-enable": {
        const pending=enablePush(this.backend.auth?.currentUser);
        return this.action(async()=>{try{this.s.push={...await pending,message:"Alerts enabled on this device. Delivery depends on your connection and device settings."};}catch(error){this.s.push.message=error.message;}},"push");
      }
      case "push-disable": return this.action(async()=>{await disablePush(this.backend.auth?.currentUser);this.s.push={...await pushState(this.s.session.uid),message:"Alerts are off on this device."};});
      case "push-notify": return this.action(async()=>{const result=await pushRequest(this.backend.auth?.currentUser,"notify/"+encodeURIComponent(node.dataset.id));this.toast(result.failed?"Some alerts could not send. Try again later.":result.sent?"Pickup alert sent.":result.already?"The pickup alert was already sent.":"No enabled device alerts. The student can check Orders.");});
      case "push-test": return this.action(async()=>{const result=await pushRequest(this.backend.auth?.currentUser,"test");this.s.push.message=result.sent?"Test alert sent. Check your notifications.":"No alert was sent. Re-enable alerts and try again.";});
      case "google":
        return this.action(() => this.backend.google());
      case "toggle-auth":
        this.s.auth.error = null;
        this.s.auth.register = !this.s.auth.register;
        this.s.auth.password = "";
        return this.render();
      case "toggle-password":
        this.s.auth.visible = !this.s.auth.visible;
        return this.render();
      case "reset-password":
        return this.action(async () => {
          if (!validEmail(this.s.auth.email)) throw new Error("Enter your email above, then tap Reset password.");
          await this.backend.reset(this.s.auth.email);
          this.toast("If an account exists, a reset email will arrive shortly.");
        });
      case "use-demo":
        return this.action(() => this.setBackend("demo"));
      case "use-live":
        return this.action(() => this.setBackend("live"));
      case "demo-student":
        return this.action(() => this.backend.login(false));
      case "demo-admin":
        return this.action(() => this.backend.login(true));
      case "switch-role":
        return this.action(() => this.backend.login(!this.s.session.admin));
      case "shop-settings":
        return this.go("settings");
      case "account-back":
        return this.go("account");
      case "new-request":
        return this.go("request");
      case "back":
        return this.go("orders");
      case "order":
        return this.go("detail", node.dataset.id);
      case "filter":
        this.s.filter = node.dataset.filter;
        return this.render();
      case "refresh":
        return this.action(() => this.backend.refresh());
      case "sign-out":
        return this.confirm("Sign out of PrintEase?", "Your submitted orders stay saved. The unfinished draft on this device will be removed.", "Sign out", async () => {
          if(this.s.mode!=="demo")await disablePush(this.backend.auth?.currentUser).catch(()=>{});
          await this.clearDraft();
          await this.backend.signOut();
          this.s.route = "home";
          history.replaceState(null, "", "#home");
        });
      case "discard":
        return this.confirm("Discard this draft?", "The selected PDF and print preferences will be removed from this browser.", "Discard", async () => {
          await this.clearDraft();
          this.s.route = "home";
          history.replaceState(null, "", "#home");
        });
      case "review":
        return this.review();
      case "confirm": {
        const callback = this.modal?.callback;
        this.closeModal();
        return this.action(callback);
      }
      case "estimate": return this.pickupEstimateDialog();
      case "estimate-save": {
        const minutes=Number(document.getElementById('pickup-minutes')?.value),id=node.dataset.id;
        if(!Number.isInteger(minutes)||minutes<1||minutes>10080)return;
        this.closeModal();return this.action(async()=>{await this.backend.setPickupTime(id,Date.now()+minutes*60000);this.toast('Pickup estimate saved. The student can see it.');});
      }
      case "estimate-clear": {
        const id=node.dataset.id;this.closeModal();return this.action(async()=>{await this.backend.setPickupTime(id,null);this.toast('Pickup estimate cleared.');});
      }
      case "collect-confirm": {
        const id=node.dataset.id,code=document.getElementById('collection-code')?.value||'';
        if(code.replace(/[^A-Za-z0-9]/g,'').toUpperCase()!==collectionCode(id))return;
        this.closeModal();return this.action(async()=>{await this.backend.transition(id,'Completed');this.toast('Collection verified. The request is complete.');});
      }
      case "reorder": return this.reorder();
      case "status":
        return this.status(node.dataset.status);
      case "reject-confirm": {
        const note = document.getElementById("reject-reason")?.value.trim();
        if (!note) return;
        const id = this.s.selected;
        this.closeModal();
        return this.action(async () => {
          await this.backend.transition(id, "Rejected", note);
          this.toast("Request rejected. The student can see your reason.");
        });
      }
      case "draft-preview":
        return this.previewPdf(this.s.draft.pdf.name, async () => new Uint8Array(await this.s.draft.file.arrayBuffer()));
      case "order-preview": {
        const order = this.s.orders.find((o) => o.id === this.s.selected);
        if (order) return this.previewPdf(order.fileName, () => this.backend.bytes(order));
        return;
      }
      case "preview-prev":
        return this.changePage(-1);
      case "preview-next":
        return this.changePage(1);
      case "close-modal":
        return this.closeModal();
      case "install":
        return this.installHelp();
    }
  }
  onInput(event) {
    const target = event.target;
    if (!target || target.disabled) return;
    const id = target.id;
    if (id?.startsWith("auth-")) {
      const key = id.slice(5);
      if (["name", "email", "password"].includes(key)) this.s.auth[key] = target.value;
      return;
    }
    if (id === "order-search") {
      this.s.search = target.value;
      const list = document.getElementById("orders-list");
      import("./view.mjs").then(({ ordersList }) => {
        if (list?.isConnected) list.innerHTML = ordersList(this.s);
      });
      return;
    }
    if (id === "pdf-input" && event.type === "change") {
      const file = target.files?.[0];
      if (file) this.pick(file);
      return;
    }
    if(id==='collection-code'){
      const confirm=document.querySelector('[data-action="collect-confirm"]');
      if(confirm)confirm.disabled=target.value.replace(/[^A-Za-z0-9]/g,'').toUpperCase()!==collectionCode(confirm.dataset.id);
      return;
    }
    if(id==='pickup-minutes'){
      const value=Number(target.value),confirm=document.querySelector('[data-action="estimate-save"]');
      if(confirm)confirm.disabled=!Number.isInteger(value)||value<1||value>10080;
      return;
    }
    if (id === "reject-reason") {
      const confirm = document.querySelector('[data-action="reject-confirm"]');
      if (confirm) confirm.disabled = !target.value.trim();
      return;
    }
    if (this.s.route === "settings" && (target.dataset.setting || target.dataset.rate)) {
      this.s.shopEdit ??= structuredClone(this.s.settings);
      const edit = this.s.shopEdit;
      if (target.dataset.rate) edit.rates[target.dataset.rate] = target.value;
      else if (["acceptingOrders","ratesConfirmed"].includes(target.dataset.setting)) edit.acceptingOrders = target.checked;
      else edit[target.dataset.setting] = target.dataset.setting === "retentionDays" ? Number(target.value) : target.value;
      return;
    }
    if (this.s.route !== "request") return;
    const d = this.s.draft;
    if (id === "copies") {
      d.rawCopies = target.value;
      const number = Number(target.value);
      if (Number.isInteger(number) && number >= 1 && number <= 100) d.options.copies = number;
    } else if (id === "page-ranges") {
      d.rawRanges = target.value;
      try {
        d.options.pageRanges = parsePageRanges(target.value, d.options.pages);
        delete d.options.selectedPages;
        d.rangeError = null;
      } catch (error) {
        d.rangeError = error.message;
      }
    } else if (id === "color") d.options.color = target.checked;
    else if (id === "duplex") d.options.duplex = target.checked;
    else if (target.name === "paper") d.options.paper = target.value;
    else return;
    this.updateEstimate();
    this.saveDraft().catch((e2) => this.toast(errorMessage(e2)));
  }
  async onSubmit(event) {
    if (event.target.id === "shop-form") {
      event.preventDefault();
      if (this.s.busy || !this.s.session?.admin) return;
      return this.action(async () => {
        const edit = structuredClone(this.s.shopEdit || this.s.settings);
        for (const key of ["name", "address", "pickupHours", "contactEmail", "facebookUrl"]) edit[key] = edit[key].trim();
        for (const key of ["bw", "color", "legalExtra"]) {
          if (String(edit.rates[key]).trim() === "") throw new Error("Enter all three print rates.");
          edit.rates[key] = Number(edit.rates[key]);
        }
        edit.retentionDays = 0;
        validateShop(edit);
        await this.backend.saveSettings(edit);
        this.s.route = "account";
        this.s.shopEdit = null;
        history.replaceState(null, "", "#account");
        this.toast("Shop details saved. Existing orders keep their quoted price.");
      });
    }
    if (event.target.id !== "auth-form") return;
    event.preventDefault();
    if (this.s.busy) return;
    const form = event.target;
    const a = { ...this.s.auth, email: form.elements.email?.value ?? this.s.auth.email, password: form.elements.password?.value ?? this.s.auth.password, name: form.elements.name?.value ?? this.s.auth.name };
    this.s.auth = { ...this.s.auth, ...a, error: null };
    return this.action(async () => {
      if (!validEmail(a.email)) throw new Error("Enter a valid email address.");
      if (a.register && (a.name.trim().length < 2 || a.password.length < 8)) throw new Error("Enter your full name and a password with at least 8 characters.");
      await this.backend.email(a.email, a.password, a.register ? a.name : null);
    });
  }
  get draftKey() {
    return `draft-${this.s.mode}-${this.s.session?.uid || "none"}`;
  }
  async loadDraft() {
    const key = this.draftKey;
    try {
      const saved = await db.get("settings", key);
      if (saved?.value && key === this.draftKey) {
        const file = await db.get("files", key);
        if (file?.blob && key === this.draftKey && !this.s.draft.pdf) {
          validateOptions(saved.value.options);
          this.s.draft = { ...saved.value, file: file.blob };
        }
      }
    } catch {
      this.s.draftWarning = "This browser could not restore a saved draft. Choose your PDF again.";
    }
  }
  async saveDraft() {
    if (!this.s.session || !this.s.draft.pdf) return true;
    const { file, ...value } = this.s.draft;
    try {
      await db.put("settings", { id: this.draftKey, value });
      return true;
    } catch {
      this.s.draftWarning = "A recovery copy could not be saved on this device. Keep this tab open until you submit.";
      return false;
    }
  }
  async clearDraft() {
    const key = this.draftKey;
    this.s.draft = freshDraft();
    this.s.requestError = null;
    this.s.draftWarning = null;
    try {
      await db.transact(["settings", "files"], (tx) => {
        tx.objectStore("settings").delete(key);
        tx.objectStore("files").delete(key);
      });
    } catch {
    }
  }
  pick(file) {
    if (this.s.busy) return;
    const key = this.draftKey, uid = this.s.session?.uid, generation = this.generation;
    this.s.fileReading = true;
    return this.action(async () => {
      try {
        const { inspectPdf } = await import("./pdf.mjs");
        const pdf = await inspectPdf(file);
        if (generation !== this.generation || uid !== this.s.session?.uid) return;
        const { selectedPages: ignored, ...oldOptions } = this.s.draft.options;
        const draft = { id: crypto.randomUUID(), pdf, file, options: { ...oldOptions, pages: pdf.pages, pageRanges: [] }, rawRanges: "", rangeError: null, rawCopies: this.s.draft.rawCopies };
        this.s.draft = draft;
        this.s.draftWarning = null;
        const { file: blob, ...value } = draft;
        try {
          await db.transact(["settings", "files"], (tx) => {
            tx.objectStore("settings").put({ id: key, value });
            tx.objectStore("files").put({ id: key, blob });
          });
        } catch {
          this.s.draftWarning = "Your PDF is selected, but a recovery copy could not be saved. Keep this tab open until you submit.";
        }
      } finally {
        this.s.fileReading = false;
      }
    }, "request");
  }
  updateEstimate() {
    const d = this.s.draft, o = d.options, cost = estimate(o, this.s.settings.rates), valid = !!d.rawCopies.trim() && Number.isInteger(Number(d.rawCopies)) && Number(d.rawCopies) >= 1 && Number(d.rawCopies) <= 100 && !d.rangeError;
    const set = (id, value) => {
      const node = document.getElementById(id);
      if (node) node.textContent = value;
    };
    set("summary-copies", valid ? o.copies : "\u2014");
    set("summary-mode", o.color ? "Color" : "Black & white");
    set("summary-sides", o.duplex ? "Double-sided" : "Single-sided");
    set("summary-paper", o.paper);
    set("summary-total", d.pdf && valid ? pesos(cost.total) : "\u2014");
    set("mobile-total", d.pdf && valid ? pesos(cost.total) : "\u2014");
    set("summary-formula", d.pdf && valid ? `${selectedPages(o)} selected pages \xD7 ${o.copies} copies \xD7 ${pesos(cost.rate)} per printed side. ${cost.sheets} sheets \xB7 Payment at pickup` : "Choose a document to calculate your estimate.");
    set("range-help", d.rangeError || "Leave blank for all pages, or enter 1-3, 5, 8-10.");
    document.getElementById("range-help")?.classList.toggle("danger-text", !!d.rangeError);
    document.getElementById("page-ranges")?.setAttribute("aria-invalid", String(!!d.rangeError));
    set("copy-help", Number(d.rawCopies) >= 1 && Number(d.rawCopies) <= 100 ? "Choose 1\u2013100 copies." : "Enter a whole number from 1 to 100.");
    document.getElementById("copy-help")?.classList.toggle("danger-text", !valid);
    document.getElementById("copies")?.setAttribute("aria-invalid", String(!valid));
    document.querySelectorAll('[data-action="review"]').forEach((button) => button.disabled = !d.pdf || !valid || this.s.busy || !this.s.settings.acceptingOrders || !!this.s.settingsError);
  }
  review() {
    const d = this.s.draft;
    if (!d.pdf || !d.rawCopies.trim() || Number(d.rawCopies) !== d.options.copies || d.rangeError) return this.toast("Choose a PDF and a valid number of copies.");
    if (!this.s.settings.acceptingOrders || this.s.settingsError) return this.toast(this.s.settingsError || "The shop has paused new requests. Your draft is saved.");
    const quotedVersion = this.s.settings.version;
    const cost = estimate(d.options, this.s.settings.rates);
    const summary=`<div class="review-sheet"><div class="review-file">${icon('pdf')}<div><b>${e(d.pdf.name)}</b><p class="small">${d.pdf.pages} pages in PDF · ${Math.ceil(d.pdf.bytes/1024)} KB</p></div></div><dl class="review-options">${[['Pages to print',pageLabel(d.options)],['Copies',d.options.copies],['Paper',d.options.paper],['Print mode',d.options.color?'Color':'Black & white'],['Sides',d.options.duplex?'Double-sided':'Single-sided'],['Pickup at',this.s.settings.name]].map(([label,value])=>`<div><dt>${e(label)}</dt><dd>${e(value)}</dd></div>`).join('')}</dl><div class="review-total"><span>Estimated total</span><strong>${pesos(cost.total)}</strong></div><p class="small">${this.s.settings.ratesConfirmed?'Pay at pickup.':'Sample prices. Confirm the actual price with the shop.'} The shop reviews your PDF before assigning a queue number.</p></div>`;
    this.confirm("Ready to send?", `${d.pdf.name}

${pageLabel(d.options)} \xD7 ${d.options.copies} copies
${d.options.paper} \xB7 ${d.options.color ? "Color" : "Black & white"} \xB7 ${d.options.duplex ? "Double-sided" : "Single-sided"}

Estimated total: ${pesos(cost.total)}
Pay at pickup. The shop reviews your PDF before assigning a queue number.`, "Submit request", async () => {
      if (quotedVersion !== this.s.settings.version) throw new Error("Shop details or rates changed. Review your updated total.");
      await this.saveDraft();
      await this.backend.submit(this.s.draft, (p) => {
        this.s.progress = p;
        const bar = document.querySelector(".progress span");
        if (bar) bar.style.width = Math.round(p * 100) + "%";
        const label = document.getElementById("progress-text");
        if (label) label.textContent = p >= 1 ? "Saving your request\u2026" : `Uploading your PDF \xB7 ${Math.round(p * 100)}%`;
      });
      await this.clearDraft();
      this.s.route = "orders";
      history.replaceState(null, "", "#orders");
      this.toast("Request submitted. Follow its progress in Orders.");
    }, summary);
  }
  status(next) {
    const order = this.s.orders.find((o) => o.id === this.s.selected);
    if (!order) return;
    if(next==='Completed'){
      this.openModal(`<div class="dialog-content stack"><h2>Verify collection</h2><p class="small">Ask the student for the six-character code shown on their request before handing over the prints.</p><label>Collection code<input id="collection-code" type="text" maxlength="12" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="Enter student’s code"></label><div class="dialog-actions"><button class="button outline" data-action="close-modal">Go back</button><button class="button" data-action="collect-confirm" data-id="${e(order.id)}" disabled>Confirm collection</button></div></div>`);return;
    }

    if (next === "Rejected") {
      this.openModal(`<div class="dialog-content"><div class="row between"><h2>Why reject this request?</h2><button class="icon-button" data-action="close-modal" aria-label="Close">${icon("close")}</button></div><p class="small">Tell the student what to change before submitting again.</p><label>Reason shown to student<textarea id="reject-reason" maxlength="500" rows="4"></textarea></label><div class="dialog-actions"><button class="button outline" data-action="close-modal">Keep request</button><button class="button danger" data-action="reject-confirm" disabled>Confirm rejection</button></div></div>`);
      return;
    }
    this.confirm(next === "Cancelled" ? "Cancel this request?" : next === "Accepted" ? "Accept this request?" : `Mark as ${next}?`, next === "Cancelled" ? "The shop will no longer process this PDF. You can submit a new request later." : next === "Accepted" ? "Check the PDF, page count and printing preferences. A unique queue number will be assigned." : "Make sure the document is ready for the next step before confirming.", "Confirm", async () => {
      await this.backend.transition(order.id, next);
      if(next==="Ready for Pickup"&&this.s.mode!=="demo"){
        try{const result=await pushRequest(this.backend.auth?.currentUser,"notify/"+encodeURIComponent(order.id));if(result.failed){this.toast("Marked ready. Some device alerts could not send; the student can still check Orders.");return;}}
        catch{this.toast("Marked ready. Background alerts could not send; the student can still check Orders.");return;}
      }
      this.toast(next === "Accepted" ? "Accepted. A unique queue number has been assigned." : next === "Cancelled" ? "Request cancelled." : `Order updated: ${next}.`);
    });
  }
  pickupEstimateDialog(){
    const order=this.s.orders.find(item=>item.id===this.s.selected);
    if(!this.s.session?.admin||!order||!['Pending','Accepted','Printing'].includes(order.status))return;
    const minutes=order.expectedReadyAt?Math.min(10080,Math.max(1,Math.ceil((order.expectedReadyAt-Date.now())/60000))):30;
    this.openModal(`<div class="dialog-content stack"><h2>Set pickup estimate</h2><p class="small">Give the student a realistic ready time. You can update it if the workload changes.</p><label>Minutes from now<input id="pickup-minutes" type="number" inputmode="numeric" min="1" max="10080" value="${minutes}"><small>Choose 1 minute to 7 days.</small></label><div class="dialog-actions">${order.expectedReadyAt?`<button class="button ghost" data-action="estimate-clear" data-id="${e(order.id)}">Clear estimate</button>`:''}<button class="button outline" data-action="close-modal">Go back</button><button class="button" data-action="estimate-save" data-id="${e(order.id)}">Save estimate</button></div></div>`);
  }
  reorder(){
    const order=this.s.orders.find(item=>item.id===this.s.selected);
    if(!order||this.s.session?.admin||order.ownerId!==this.s.session?.uid||!terminal.includes(order.status)||order.documentDeletedAt)return;
    const prepare=()=>this.prepareReorder(order);
    if(this.s.draft.pdf)return this.confirm('Replace the unfinished draft?','The new draft will use this previous PDF and print options. You will review current prices before sending.','Prepare repeat order',prepare);
    return this.action(prepare);
  }
  async prepareReorder(order){
    const uid=this.s.session?.uid,generation=this.generation,key=this.draftKey;
    const bytes=await this.backend.bytes(order),file=new Blob([bytes],{type:'application/pdf'});file.name=order.fileName;
    const {inspectPdf}=await import('./pdf.mjs'),pdf=await inspectPdf(file);
    if(uid!==this.s.session?.uid||generation!==this.generation)return;
    const options=structuredClone(order.options);delete options.selectedPages;options.pages=pdf.pages;validateOptions(options);
    const draft={id:crypto.randomUUID(),pdf,file,options,rawCopies:String(options.copies),rawRanges:rangeText(options.pageRanges),rangeError:null};
    this.s.draft=draft;this.s.draftWarning=null;this.s.requestError=null;
    const {file:blob,...value}=draft;
    try{await db.transact(['settings','files'],tx=>{tx.objectStore('settings').put({id:key,value});tx.objectStore('files').put({id:key,blob});});}
    catch{this.s.draftWarning='Your repeat order is ready, but a recovery copy could not be saved. Keep this tab open until you submit.';}
    this.s.route='request';history.replaceState(null,'','#request');
    this.toast('Repeat draft ready. Review current prices before submitting.');
  }
  confirm(title, text, label, callback, details = "") {
    this.openModal(`<div class="dialog-content"><h2 id="dialog-title">${e(title)}</h2>${details || `<p style="white-space:pre-line">${e(text)}</p>`}<div class="dialog-actions"><button class="button outline" data-action="close-modal">Go back</button><button class="button" data-action="confirm">${e(label)}</button></div></div>`, callback);
  }
  openModal(html, callback = null, preview = false) {
    this.closeModal();
    const root = document.getElementById("modal-root");
    root.innerHTML = `<dialog aria-label="${preview ? "PDF preview" : "PrintEase dialog"}" class="${preview ? "preview-dialog" : ""}">${html}</dialog>`;
    const dialog = root.querySelector("dialog");
    this.modal = { dialog, callback, previous: document.activeElement };
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      if (!this.s.busy) this.closeModal();
    });
    dialog.showModal();
    dialog.querySelector("button,input,textarea,a")?.focus();
  }
  closeModal() {
    if (this.preview) {
      this.preview.generation++;
      this.preview.doc?.destroy().catch(() => {
      });
      if (this.preview.url) URL.revokeObjectURL(this.preview.url);
      this.preview = null;
    }
    const previous = this.modal?.previous;
    this.modal?.dialog?.close();
    this.modal = null;
    const root = document.getElementById("modal-root");
    if (root) root.innerHTML = "";
    previous?.isConnected && previous.focus({ preventScroll: true });
  }
  installHelp() {
    this.openModal(`<div class="dialog-content"><div class="row between"><h2>PrintEase on your iPhone</h2><button class="icon-button" data-action="close-modal" aria-label="Close">${icon("close")}</button></div><p>Open this link in <b>Safari</b>, then:</p><ol class="install-steps"><li>Tap <b>Share</b> ${icon("share")}.</li><li>Scroll down and select <b>Add to Home Screen</b>.</li><li>Keep the name <b>PrintEase</b> and tap <b>Add</b>.</li></ol><p class="small">PrintEase’s Firebase sign-in is separate from your browser account. Live orders require internet.</p><button class="button" data-action="close-modal">Got it</button></div>`);
  }
  async previewPdf(name, load) {
    this.openModal(`<div class="preview-shell"><header class="preview-head"><h2 class="grow">${e(name)}</h2><button class="icon-button" data-action="close-modal" aria-label="Close PDF">${icon("close")}</button></header><div class="preview-pages" id="preview-pages"><div class="spinner" aria-label="Loading PDF"></div></div><footer class="preview-footer"><button class="button outline" data-action="preview-prev" disabled>Previous</button><span id="page-label" class="small">Loading\u2026</span><button class="button outline" data-action="preview-next" disabled>Next</button><a id="pdf-download" class="icon-button hidden" download="${e(name)}" aria-label="Download PDF">${icon("download")}</a></footer></div>`, null, true);
    const current = { generation: 0, doc: null, page: 1, url: null, rendering: false };
    this.preview = current;
    try {
      const bytes = await load();
      if (this.preview !== current) return;
      const { openPdf } = await import("./pdf.mjs");
      const doc = await openPdf(bytes);
      if (this.preview !== current) {
        await doc.destroy();
        return;
      }
      current.doc = doc;
      current.url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
      const a = document.getElementById("pdf-download");
      a.href = current.url;
      a.classList.remove("hidden");
      await this.drawPreview();
    } catch (error) {
      if (this.preview === current) {
        document.getElementById("preview-pages").innerHTML = `<div class="card stack"><h3>PDF preview unavailable</h3><p>${e(errorMessage(error))}</p><button class="button outline" data-action="close-modal">Close and try again</button></div>`;
      }
    }
  }
  async drawPreview() {
    const p = this.preview;
    if (!p?.doc || p.rendering) return;
    p.rendering = true;
    const area = document.getElementById("preview-pages");
    area.innerHTML = '<canvas aria-label="PDF page"></canvas>';
    const canvas = area.querySelector("canvas");
    document.getElementById("page-label").textContent = `${p.page} / ${p.doc.numPages}`;
    this.modal.dialog.querySelectorAll('[data-action^="preview-"]').forEach((b) => b.disabled = true);
    try {
      const { renderPage } = await import("./pdf.mjs");
      await renderPage(p.doc, p.page, canvas);
    } catch (error) {
      if (this.preview === p) this.toast(errorMessage(error));
    } finally {
      p.rendering = false;
      if (this.preview === p) {
        document.querySelector('[data-action="preview-prev"]').disabled = p.page <= 1;
        document.querySelector('[data-action="preview-next"]').disabled = p.page >= p.doc.numPages;
      }
    }
  }
  async changePage(delta) {
    const p = this.preview;
    if (!p?.doc || p.rendering) return;
    p.page = Math.max(1, Math.min(p.doc.numPages, p.page + delta));
    await this.drawPreview();
  }
  async registerWorker() {
    if (!("serviceWorker" in navigator) || location.protocol === "file:") return;
    try {
      await navigator.serviceWorker.register("/sw.js");
    } catch {
    }
  }
  close() {
    document.removeEventListener("error", this.imageError, true);
    this.backend?.close();
    this.closeModal();
    clearTimeout(this.toastTimer);
    document.removeEventListener("click", this.click);
    document.removeEventListener("input", this.input);
    document.removeEventListener("change", this.input);
    document.removeEventListener("submit", this.submit);
    window.removeEventListener("hashchange", this.hash);
    window.removeEventListener("online", this.connection);
    window.removeEventListener("offline", this.connection);
    window.removeEventListener("focus", this.focus);
  }
}
function readMode() {
  try {
    return localStorage.getItem("pe_mode") === "demo" ? "demo" : "live";
  } catch {
    return "live";
  }
}
function validEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());
}
if (typeof document !== "undefined" && !globalThis.__PE_TEST__) {
  const app = new PrintEase();
  app.start().catch((error) => app.toast(errorMessage(error)));
}
export {
  PrintEase
};
