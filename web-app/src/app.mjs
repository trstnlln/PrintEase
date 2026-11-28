import { MAX_ATTACHMENTS, batchRangeMessage, batchRangesValid, hasPartialRanges, requestOutOfStock, validCopies, validFileOptions } from "./attachment.mjs";
import { clockMinutes } from "./pickup-schedule.mjs";
import { customPaper, requiresPaperQuote, paperLabel, printScaleLabel } from "./paper.mjs";
import { addons, MAX_QUOTE } from "./review.mjs";
import { firebaseConfig } from "./config.mjs";
import { defaults, estimate, pesos, escapeHtml as e, errorMessage, validateOptions, MAX_BYTES, defaultShop, validateShop, parsePageRanges, rangeText, pageLabel, collectionCode, terminal } from "./model.mjs";
import { authView, shell, dashboard, requestView, detailView, accountView, settingsView, icon } from "./view.mjs";
import * as db from "./db.mjs";
import { mountPreviewPages } from "./pdf-preview.mjs";
import { handoutName, handoutOptions, printedSides, slideLayouts, slidesPerSide } from "./handout.mjs";
import {pushState,reconcilePushUser,enablePush,disablePush,pushRequest,pickupAlertMessage} from "./push.mjs";
const freshDraft = () => ({ id: crypto.randomUUID(), pdf: null, file: null, options: defaults(), rawCopies: "1", rawRanges: "", rangeError: null });
class PrintEase {
  constructor({ root = document.getElementById("app"), demo = false } = {}) {
    this.root = root;
    this.s = { mode: demo ? "demo" : readMode(), session: null, orders: [], settings: defaultShop(), reviewAvailable: false, paperSizesAvailable: false, printToolsAvailable: false, submitted: null, queueStatus: null, settingsError: null, shopEdit: null, ready: false, cached: false, syncError: null, busy: false, progress: 0, route: "home", selected: "", filter: "All", search: "", online: navigator.onLine, auth: { register: false, name: "", email: "", password: "", visible: false }, draft: freshDraft(), requestError: null, draftWarning: null, fileReading: false, push:{supported:false,enabled:false,message:null} };
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
    this.s = { ...this.s, mode, session: null, orders: [], settings: defaultShop(), reviewAvailable: false, paperSizesAvailable: false, printToolsAvailable: false, submitted: null, queueStatus: null, settingsError: null, shopEdit: null, ready: false, syncError: null, route: "home", draft: freshDraft(), auth: { ...this.s.auth, password: "" } };
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
        this.s.submitted = null;
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
      case "push-notify": return this.action(async()=>{const result=await pushRequest(this.backend.auth?.currentUser,"notify/"+encodeURIComponent(node.dataset.id));this.toast(pickupAlertMessage(result));});
      case "push-test":
        return this.action(async () => {
          const result = await pushRequest(this.backend.auth?.currentUser, "test");
          this.s.push.message = result.sent ? "Test alert sent. Check your notifications."
            : result.failed ? "The notification service could not send the test. Keep Orders open for updates."
            : "Turn off alerts here, then enable them again to reconnect this device.";
        });
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
      case "final-quote":
      case "request-correction":
        return this.staffReviewDialog(action === "final-quote" ? "quote" : "correction");
      case "approve-quote":
      case "decline-quote": {
        const order = this.s.orders.find(item => item.id === this.s.selected);
        if (order?.review?.stage !== "quoted") return;
        const approve = action === "approve-quote";
        const title = approve ? "Approve this price?" : "Decline this quote?";
        const detail = approve
          ? `Pay ${pesos(order.review.quote)} when you collect your prints. The shop can accept the request after approval.`
          : "The shop can send another price. Printing stays on hold.";
        return this.confirm(title, detail, approve ? "Approve price" : "Decline price", async () => {
          await this.backend.reviewOrder(order.id, approve ? "approve" : "decline", order.review.version);
          this.toast(approve ? "Price approved. The shop can now accept your request." : "Quote declined.");
        });
      }
      case "edit-correction": {
        const order = this.s.orders.find(item => item.id === this.s.selected);
        if (order?.review?.stage !== "correction") return;
        const revision = this.s.draft.revision;
        if (revision?.id === order.id && revision.version === order.review.version) return this.go("request");
        const prepare = () => this.prepareReorder(order, true);
        if (this.s.draft.pdf) return this.confirm("Replace your draft?", "The corrected request will use this draft slot.", "Edit request", prepare);
        return this.action(prepare);
      }
      case "cleanup-pdf": {
        const id = this.s.selected;
        return this.confirm("Remove the stored PDF?", "This removes the document and any earlier versions for this request. Your order history stays available.", "Remove PDF", async () => {
          const removed = await this.backend.cleanupPdf(id);
          this.toast(removed ? "PDF removed. The order history is still saved." : "More PDF versions remain. Run cleanup again.");
        });
      }
      case "remove-print-file":
        return this.removePrintFile(Number(node.dataset.index));
      case "resize-image": {
        const index = Number(node.dataset.index);
        const input = document.getElementById("image-size-" + index);
        const percent = Number(input?.value);
        return this.resizeImage(index, percent);
      }
      case "download-print-file": {
        const order = this.s.orders.find(item => item.id === this.s.selected);
        if (!order || !this.s.session?.admin) return;
        const index = Number(node.dataset.index);
        const file = index === 0 ? order : order.attachments?.[index - 1];
        if (!file) return;
        return this.action(async () => {
          const bytes = await this.backend.bytes(order, index);
          const url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
          const link = document.createElement("a");
          link.href = url;
          link.download = /\.pdf$/i.test(file.fileName) ? file.fileName : file.fileName + ".pdf";
          document.body.append(link);
          link.click();
          link.remove();
          setTimeout(() => URL.revokeObjectURL(url), 30000);
        });
      }
      case "mark-printed": {
        const order = this.s.orders.find(item => item.id === this.s.selected);
        if (!order) return;
        const index = Number(node.dataset.index);
        return this.action(() => this.backend.markPrinted(order.id, index, !order.printedFiles?.includes(index)));
      }
      case "attachment-preview": {
        const order = this.s.orders.find(item => item.id === this.s.selected);
        const position = Number(node.dataset.index);
        const attachment = order?.attachments?.[position-1];
        if (!attachment) return;
        return this.previewPdf(attachment.fileName, () => this.backend.bytes(order, position), attachment.options);
      }
      case "estimate": return this.pickupEstimateDialog();
      case "estimate-save": {
        const minutes=Number(document.getElementById('pickup-minutes')?.value),id=node.dataset.id;
        if(!Number.isInteger(minutes)||minutes<1||minutes>10080)return;
        this.closeModal();return this.action(async()=>{await this.backend.setPickupTime(id,Date.now()+minutes*60000);this.toast('Pickup estimate saved. The customer can see it.');});
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
          this.toast("Request rejected. The customer can see your reason.");
        });
      }
      case "draft-preview": {
        const index = Number(node.dataset.index);
        const item = index === 0 ? this.s.draft : this.s.draft.attachments?.[index - 1];
        if (!item?.pdf || item.paperError || item.rangeError) return;
        const perSide = slidesPerSide(item.options);
        if (perSide > 1) {
          return this.previewPdf(handoutName(item.pdf.name, perSide), async () => {
            const { prepareHandout } = await import("./handout-pdf.mjs");
            return new Uint8Array(await (await prepareHandout(item)).file.arrayBuffer());
          });
        }
        return this.previewPdf(item.pdf.name, async () => new Uint8Array(await item.file.arrayBuffer()), item.options);
      }
      case "order-preview": {
        const order = this.s.orders.find((o) => o.id === this.s.selected);
        if (order) return this.previewPdf(order.fileName, () => this.backend.bytes(order), order.options);
        return;
      }
      case "preview-layout":
        if (this.preview?.doc) {
          this.preview.layout = !this.preview.layout;
          return this.showPreviewPages();
        }
        return;
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
    if ((id === "pdf-input" || id === "attachment-input") && event.type === "change") {
      const files = Array.from(target.files ?? []);
      if (files.length) return this.pickFiles(files, id === "attachment-input");
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
    if (this.s.route === "settings" && target.dataset.stock) {
      const edit = this.s.shopEdit ??= structuredClone(this.s.settings);
      const stock = edit.paperStock ??= { available: [], out: [] };
      for (const status of ["available", "out"]) {
        stock[status] = stock[status].filter(size => size !== target.dataset.stock);
        if (target.value === status) stock[status].push(target.dataset.stock);
      }
      return;
    }
    if (this.s.route === "settings" && target.dataset.calendar) {
      const edit = this.s.shopEdit ??= structuredClone(this.s.settings);
      const key = target.dataset.calendar;
      if (key === "enabled") {
        edit.pickupSchedule = target.checked ? { days: [], open: 360, close: 1320, cutoff: null, closed: "" } : null;
        this.render();
      } else if (key === "day") {
        const day = Number(target.value);
        edit.pickupSchedule.days = target.checked
          ? [...new Set([...edit.pickupSchedule.days, day])].sort()
          : edit.pickupSchedule.days.filter(value => value !== day);
      } else if (key === "closed") edit.pickupSchedule.closed = target.value;
      else edit.pickupSchedule[key] = clockMinutes(target.value);
      return;
    }
    if (this.s.route === "settings" && (target.dataset.setting || target.dataset.rate)) {
      this.s.shopEdit ??= structuredClone(this.s.settings);
      const edit = this.s.shopEdit;
      if (target.dataset.rate) edit.rates[target.dataset.rate] = target.value;
      else if (["acceptingOrders","ratesConfirmed"].includes(target.dataset.setting)) edit[target.dataset.setting] = target.checked;
      else edit[target.dataset.setting] = target.dataset.setting === "retentionDays" ? Number(target.value) : target.value;
      return;
    }
    if (this.s.route !== "request") return;
    const d = this.s.draft;
    const field = target.dataset.printField;
    if (field) {
      const card = target.closest("[data-print-file-index]");
      const index = Number(card?.dataset.printFileIndex);
      const item = index === 0 ? d : d.attachments?.[index - 1];
      if (!item) return;
      if (field === "copies") {
        item.rawCopies = target.value;
        const copies = Number(target.value);
        if (Number.isInteger(copies) && copies >= 1 && copies <= 100) item.options.copies = copies;
      } else if (field === "ranges") {
        item.rawRanges = target.value;
        try {
          item.options.pageRanges = parsePageRanges(target.value, item.options.pages);
          delete item.options.selectedPages;
          item.rangeError = null;
        } catch (error) {
          item.rangeError = error.message;
        }
      } else if (field === "paper") {
        item.paperError = null;
        if (target.value === "Custom") {
          item.paperWidth = "";
          item.paperHeight = "";
          item.paperError = "Enter the width and height in millimeters.";
          item.options.paper = "Custom 210x297 mm";
        } else item.options.paper = target.value;
        this.render();
      } else if (field === "paperWidth" || field === "paperHeight") {
        item[field] = target.value;
        const value = customPaper(card.querySelector('[data-print-field="paperWidth"]').value, card.querySelector('[data-print-field="paperHeight"]').value);
        item.paperError = value ? null : "Enter whole millimeters from 50 to 1,000.";
        if (value) item.options.paper = value;
      } else if (field === "slidesPerSide") {
        const value = Number(target.value);
        if (!slideLayouts.includes(value) || !item.pdf || item.pdf.pages < 2) return;
        item.options.slidesPerSide = value;
        this.render();
      } else if (field === "actualSize") item.options.actualSize = target.value === "actual";
      else if (field === "color" || field === "duplex") item.options[field] = target.checked;
    } else if (target.dataset.addon && !d.revision) {
      const chosen = new Set(d.addons || []);
      if (target.checked) chosen.add(target.dataset.addon);
      else chosen.delete(target.dataset.addon);
      d.addons = addons.filter(addon => chosen.has(addon.id)).map(addon => addon.id);
    } else return;
    this.updateEstimate();
    this.saveDraft().catch(error => this.toast(errorMessage(error)));
  }
  async onSubmit(event) {
    if (event.target.id === "review-form") {
      event.preventDefault();
      if (this.s.busy) return;
      const { order, reviewAction } = this.modal ?? {};
      if (!order) return;
      const form = event.target;
      const note = form.elements.namedItem("note").value.trim();
      const quoteInput = form.elements.namedItem("quote");
      const quote = quoteInput ? Number(quoteInput.value) : null;
      if ((reviewAction === "quote" && (!Number.isSafeInteger(quote) || quote < 1 || quote > MAX_QUOTE)) ||
          (reviewAction === "correction" && !note)) {
        form.querySelector("[role=alert]").textContent = reviewAction === "quote" ? "Enter a valid price in whole pesos." : "Describe what the customer should change.";
        return;
      }
      return this.action(async () => {
        try {
          await this.backend.reviewOrder(order.id, reviewAction, order.review?.version ?? -1, { quote, note });
          this.closeModal();
          const savedMessage = reviewAction === "quote" ? "Final price sent for customer approval." : "Correction requested.";
          if (this.s.mode === "live") {
            try {
              const version = (order.review?.version ?? -1) + 1;
              await pushRequest(this.backend.auth?.currentUser, "review/" + encodeURIComponent(order.id) + "/" + version);
            } catch { /* The saved update stays visible in Orders if push is unavailable. */ }
          }
          this.toast(savedMessage);
        } catch (error) {
          form.querySelector("[role=alert]").textContent = errorMessage(error);
        }
      });
    }
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
        if (edit.pickupSchedule) edit.pickupSchedule.closed = edit.pickupSchedule.closed.split(",").map(date => date.trim()).join(",");
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
  async saveDraft(includeFile = false) {
    if (!this.s.session || !this.s.draft.pdf) return true;
    const { file, ...value } = this.s.draft;
    try {
      const id = this.draftKey;
      if (includeFile) {
        await db.transact(["settings", "files"], tx => {
          tx.objectStore("settings").put({ id, value });
          tx.objectStore("files").put({ id, blob: file });
        });
      } else await db.put("settings", { id, value });
      return true;
    } catch {
      this.s.draftWarning = "A recovery copy could not be saved on this device. Keep this tab open until you submit.";
      return false;
    }
  }
  async removePrintFile(index) {
    if (this.s.busy || !Number.isInteger(index) || index < 0) return;
    const draft = this.s.draft;
    if (!draft.pdf) return;
    const attachments = draft.attachments ?? [];
    if (index > attachments.length) return;
    const remaining = attachments.filter((_, i) => index !== i + 1);
    this.s.requestError = null;
    let saving;
    if (index > 0) {
      draft.attachments = remaining;
      saving = this.saveDraft();
    } else if (remaining.length) {
      const [next, ...extras] = remaining;
      this.s.draft = { ...freshDraft(), id: draft.id, revision: draft.revision, addons: draft.addons,
        ...next, attachments: extras };
      saving = this.saveDraft(true);
    } else {
      saving = this.clearDraft({ ...freshDraft(), revision: draft.revision, addons: draft.addons });
    }
    this.render();
    const nextButton = document.getElementById(`remove-print-file-${Math.min(index, attachments.length - 1)}`);
    (nextButton ?? document.getElementById("pdf-input"))?.focus({ preventScroll: true });
    const saved = await saving;
    if (saved === false && this.s.route === "request") this.render();
  }
  async clearDraft(next = freshDraft()) {
    const key = this.draftKey;
    this.s.draft = next;
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
    return this.pickFiles([file]);
  }
  pickFiles(files, append = false) {
    if (this.s.busy || !files.length) return;
    const draft = this.s.draft, uid = this.s.session?.uid, generation = this.generation;
    if (append && !draft.pdf) return;
    const extras = draft.attachments ?? [];
    const capacity = this.s.paperSizesAvailable ? MAX_ATTACHMENTS + 1 : 1;
    const limit = append ? capacity - 1 - extras.length : draft.pdf ? 1 : capacity;
    this.s.fileReading = true;
    return this.action(async () => {
      try {
        if (files.length > limit) throw new Error(`Choose up to ${limit} ${limit === 1 ? "file" : "files"} in this selection.`);
        const { preparePrintFile } = await import("./print-file.mjs");
        const prepared = [];
        for (const file of files) {
          const item = await preparePrintFile(file);
          prepared.push({ ...item, options: { ...defaults(), pages: item.pdf.pages }, rawCopies: "1" });
        }
        if (generation !== this.generation || uid !== this.s.session?.uid) return;
        if (append) draft.attachments = [...extras, ...prepared];
        else {
          const [first, ...rest] = prepared;
          const { selectedPages: ignored, ...oldOptions } = draft.options;
          this.s.draft = {
            ...draft, id: draft.revision ? draft.id : crypto.randomUUID(), pdf: first.pdf, file: first.file,
            attachments: [...extras, ...rest],
            options: { ...oldOptions, pages: first.pdf.pages, pageRanges: [], slidesPerSide: 1 }, rawRanges: "", rangeError: null
          };
        }
        this.s.draftWarning = null;
        await this.saveDraft(!append);
      } finally {
        this.s.fileReading = false;
      }
    }, "request");
  }
  resizeImage(index, percent) {
    const draft = this.s.draft, uid = this.s.session?.uid;
    const item = index === 0 ? draft : draft.attachments?.[index - 1];
    if (!item?.file || !item.pdf || percent === (item.pdf.imagePercent ?? 100)) return;
    return this.action(async () => {
      const { resizePrintImage } = await import("./print-file.mjs");
      const resized = await resizePrintImage(item.file, item.pdf, percent);
      if (this.s.session?.uid !== uid || draft !== this.s.draft) return;
      Object.assign(item, resized);
      await this.saveDraft(index === 0);
    }, "request");
  }
  updateEstimate() {
    const d = this.s.draft, o = d.options, cost = estimate(handoutOptions(o), this.s.settings.rates);
    const files = [d, ...(d.attachments ?? [])];
    const rangesAvailable = files.length === 1;
    const valid = files.every(validFileOptions) && batchRangesValid(d);
    const set = (id, value) => {
      const node = document.getElementById(id);
      if (node) node.textContent = value;
    };
    set("summary-copies", validCopies(d) ? o.copies : "\u2014");
    set("summary-mode", o.color ? "Color" : "Black & white");
    set("summary-sides", o.duplex ? "Double-sided" : "Single-sided");
    set("summary-paper", paperLabel(o.paper));
    set("summary-scale", slidesPerSide(o) > 1 ? `${slidesPerSide(o)} slides per side` : printScaleLabel(o));
    const quoteOnly = requiresPaperQuote(o.paper) || !!d.attachments?.length;
    set("summary-price-label", quoteOnly ? "Pricing" : "Estimated total");
    set("summary-total", d.pdf && valid && !requestOutOfStock(d, this.s.settings) ? (quoteOnly ? "Shop quote" : pesos(cost.total)) : "\u2014");
    set("mobile-total", d.pdf && valid && !requestOutOfStock(d, this.s.settings) ? (quoteOnly ? "Shop quote" : pesos(cost.total)) : "\u2014");
    set("summary-formula", quoteOnly ? "The shop checks your files and sends one price for approval." : d.pdf && valid ? `${printedSides(o)} printed sides \xD7 ${o.copies} copies \xD7 ${pesos(cost.rate)} per printed side. ${cost.sheets} sheets \xB7 Payment at pickup` : "Choose a document to calculate your estimate.");
    document.querySelectorAll("[data-print-file-index]").forEach(card => {
      const file = files[Number(card.dataset.printFileIndex)];
      const updateHelp = (name, message, invalid) => {
        const help = card.querySelector(`[data-${name}-help]`);
        if (help) {
          help.textContent = message;
          help.classList.toggle("danger-text", invalid);
        }
      };
      const rangeProblem = file.rangeError || (!rangesAvailable && hasPartialRanges(file.options) ? batchRangeMessage : "");
      const range = card.querySelector('[data-print-field="ranges"]');
      range.disabled = this.s.busy || !file.pdf || (!rangesAvailable && !rangeProblem);
      range.setAttribute("aria-invalid", String(!!rangeProblem));
      updateHelp("range", rangeProblem || (rangesAvailable ? "Leave blank for all pages, or enter 1-3, 5, 8-10." : batchRangeMessage), !!rangeProblem);
      const copies = validCopies(file);
      card.querySelector('[data-print-field="copies"]').setAttribute("aria-invalid", String(!copies));
      updateHelp("copy", copies ? "Choose 1–100 copies." : "Enter a whole number from 1 to 100.", !copies);
      updateHelp("paper", file.paperError || "Enter whole millimeters from 50 to 1,000.", !!file.paperError);
      card.querySelectorAll('[data-print-field="paperWidth"],[data-print-field="paperHeight"]').forEach(input => input.setAttribute("aria-invalid", String(!!file.paperError)));
      const sides = card.querySelector("[data-printed-sides]");
      if (sides) sides.textContent = `${printedSides(file.options)} printed sides per copy`;
      const preview = card.querySelector('[data-action="draft-preview"]');
      if (preview) preview.disabled = this.s.busy || !!file.paperError || !!file.rangeError;
    });
    document.querySelectorAll('[data-action="review"]').forEach((button) => button.disabled = !d.pdf || !valid || requestOutOfStock(d, this.s.settings) || this.s.busy || (!this.s.settings.acceptingOrders && !d.revision) || !!this.s.settingsError);
  }
  review() {
    const d = this.s.draft;
    if (!d.pdf) return this.toast("Choose a file to print.");
    if (requestOutOfStock(d, this.s.settings)) return this.toast("Choose a paper size the shop can supply.");
    if (!batchRangesValid(d)) return this.toast(batchRangeMessage);
    const invalid = [d, ...(d.attachments ?? [])].find(file => !validFileOptions(file));
    if (invalid) return this.toast(invalid.paperError || invalid.rangeError || "Enter 1–100 copies for each file.");
    if ((!this.s.settings.acceptingOrders && !d.revision) || this.s.settingsError) return this.toast(this.s.settingsError || "The shop has paused new requests. Your draft is saved.");
    const quotedVersion = this.s.settings.version;
    const cost = estimate(handoutOptions(d.options), this.s.settings.rates);
    const displayTotal = requiresPaperQuote(d.options.paper) || d.attachments?.length ? "Shop quote" : pesos(cost.total);
    const files = [d, ...(d.attachments ?? [])];
    const summary = `<div class="review-sheet review-request-files">
      ${files.map(({pdf, options}, index) => `<section>
        <header class="review-file">${icon("pdf")}<div><p class="small">File ${index + 1} of ${files.length}</p><h3 class="text-wrap" ${index === 0 ? 'tabindex="-1" autofocus' : ""}>${e(pdf.name)}</h3></div></header>
        <dl class="review-options">${[
          ["Pages to print", pageLabel(options)],
          ["Paper", paperLabel(options.paper)],
          ["Copies", options.copies],
          ["Print mode", options.color ? "Color" : "Black & white"],
          ["Sides", options.duplex ? "Double-sided" : "Single-sided"],
          slidesPerSide(options) > 1
            ? ["Slides per side", `${slidesPerSide(options)} · ${printedSides(options)} printed sides per copy`]
            : ["Page sizing", printScaleLabel(options)]
        ].map(([label, value]) => `<div><dt>${e(label)}</dt><dd>${e(value)}</dd></div>`).join("")}</dl>
      </section>`).join("")}
      <div class="review-total"><span>${displayTotal === "Shop quote" ? "Pricing" : "Estimated total"}</span><strong>${displayTotal}</strong></div>
      <p class="small">Pickup at ${e(this.s.settings.name)}. ${displayTotal === "Shop quote" ? "The shop will send one price for approval." :
        this.s.settings.ratesConfirmed ? "Pay at pickup." : "Sample estimate. The shop confirms the final price."} The shop reviews your request before assigning a queue number.</p>
    </div>`;
    const selectedAddons = addons.filter(addon => d.addons?.includes(addon.id)).map(addon => addon.label);
    const addonSummary = selectedAddons.length ? `<p><b>Requested add-ons:</b> ${e(selectedAddons.join(", "))}. The shop includes these in the final quote.</p>` : "";
    this.confirm(d.revision ? "Send the corrected request?" : "Ready to send?", "", d.revision ? "Send correction" : "Submit request", async () => {
      if (quotedVersion !== this.s.settings.version) throw new Error("Shop details or rates changed. Review your updated total.");
      await this.saveDraft();
      const progress = (p, file) => {
        this.s.progress = p;
        const bar = document.querySelector(".progress span");
        if (bar) bar.style.width = Math.round(p * 100) + "%";
        const label = document.getElementById("progress-text");
        if (label) label.textContent = p >= 1 ? "Saving your request…" : file ? `File ${file.index + 1} of ${file.total} · ${file.name} · ${Math.round(file.fraction / .97 * 100)}%` : "Preparing files…";
      };
      let request = d;
      if (files.some(file => slidesPerSide(file.options) > 1)) {
        const { prepareHandout } = await import("./handout-pdf.mjs");
        const prepared = [];
        for (const file of files) prepared.push(await prepareHandout(file));
        const [first, ...attachments] = prepared;
        request = { ...first, attachments };
      }
      if (d.revision) {
        await this.backend.reviewOrder(d.revision.id, "revise", d.revision.version, { draft: request, onProgress: progress });
      } else {
        await this.backend.submit({ ...request, addons: this.s.reviewAvailable ? d.addons ?? [] : d.addons }, progress);
      }
      await this.clearDraft();
      this.s.submitted = { id: d.revision?.id ?? d.id, revision: !!d.revision };
      this.s.route = "orders";
      history.replaceState(null, "", "#orders");
      this.toast(d.revision ? "Correction sent. The shop will review it again." : "Request submitted. Follow its progress in Orders.");
    }, summary + addonSummary);
  }
  staffReviewDialog(action) {
    const order = this.s.orders.find(item => item.id === this.s.selected);
    if (!order || !this.s.session?.admin || !this.s.reviewAvailable || order.status !== "Pending") return;
    const quote = action === "quote";
    this.openModal(`<form id="review-form" class="dialog-content stack">
      <h2>${quote ? "Set the final price" : "Request a correction"}</h2>
      <p class="small">${quote ? "Include any confirmed add-ons. The customer must approve before you can accept the request." : "Tell the customer what needs to change. They can update this same request."}</p>
      ${quote ? `<label>Final price (₱)<input name="quote" type="number" inputmode="numeric" min="1" max="${MAX_QUOTE}" required value="${order.review?.quote ?? ((requiresPaperQuote(order.options.paper) || order.attachments?.length) ? "" : estimate(order.options, order.pricing).total)}"></label>` : ""}
      <label>${quote ? "Note for the customer (optional)" : "What needs to change?"}<textarea name="note" maxlength="500" rows="3" ${quote ? "" : "required"}></textarea></label>
      <p class="danger-text" role="alert"></p>
      <div class="dialog-actions"><button type="button" class="button outline" data-action="close-modal">Back</button><button class="button" type="submit">${quote ? "Send price" : "Request correction"}</button></div>
    </form>`);
    this.modal.order = order;
    this.modal.reviewAction = action;
  }
  status(next) {
    const order = this.s.orders.find((o) => o.id === this.s.selected);
    if (!order) return;
    if (next === "Ready for Pickup" && this.s.printToolsAvailable && order.printedFiles?.length !== (order.attachments?.length ?? 0) + 1) return this.toast("Mark each file printed before pickup.");
    if(next==='Completed'){
      this.openModal(`<div class="dialog-content stack"><h2>Verify collection</h2><p class="small">Ask the customer for the six-character code shown on their request before handing over the prints.</p><label>Collection code<input id="collection-code" type="text" maxlength="12" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="Enter student’s code"></label><div class="dialog-actions"><button class="button outline" data-action="close-modal">Go back</button><button class="button" data-action="collect-confirm" data-id="${e(order.id)}" disabled>Confirm collection</button></div></div>`);return;
    }

    if (next === "Rejected") {
      this.openModal(`<div class="dialog-content"><div class="row between"><h2>Why reject this request?</h2><button class="icon-button" data-action="close-modal" aria-label="Close">${icon("close")}</button></div><p class="small">Tell the customer what to change before submitting again.</p><label>Reason shown to the customer<textarea id="reject-reason" maxlength="500" rows="4"></textarea></label><div class="dialog-actions"><button class="button outline" data-action="close-modal">Keep request</button><button class="button danger" data-action="reject-confirm" disabled>Confirm rejection</button></div></div>`);
      return;
    }
    this.confirm(next === "Cancelled" ? "Cancel this request?" : next === "Accepted" ? "Accept this request?" : `Mark as ${next}?`, next === "Cancelled" ? "The shop will no longer process this PDF. You can submit a new request later." : next === "Accepted" ? "Check the PDF, page count and printing preferences. A unique queue number will be assigned." : "Make sure the document is ready for the next step before confirming.", "Confirm", async () => {
      await this.backend.transition(order.id, next);
      if(next==="Ready for Pickup"&&this.s.mode!=="demo"){
        try {
          const result = await pushRequest(this.backend.auth?.currentUser, "notify/" + encodeURIComponent(order.id));
          if (result.failed || result.expired || result.invalid || result.untracked) {
            this.toast("Marked ready. " + pickupAlertMessage(result));
            return;
          }
        } catch {
          this.toast("Marked ready. Background alerts could not send. The customer can still check Orders.");
          return;
        }
      }
      this.toast(next === "Accepted" ? "Accepted. A unique queue number has been assigned." : next === "Cancelled" ? "Request cancelled." : `Order updated: ${next}.`);
    });
  }
  pickupEstimateDialog(){
    const order=this.s.orders.find(item=>item.id===this.s.selected);
    if(!this.s.session?.admin||!order||!['Pending','Accepted','Printing'].includes(order.status))return;
    const minutes=order.expectedReadyAt?Math.min(10080,Math.max(1,Math.ceil((order.expectedReadyAt-Date.now())/60000))):30;
    this.openModal(`<div class="dialog-content stack"><h2>Set pickup estimate</h2><p class="small">Give the customer a realistic ready time. You can update it if the workload changes.</p><label>Minutes from now<input id="pickup-minutes" type="number" inputmode="numeric" min="1" max="10080" value="${minutes}"><small>Choose 1 minute to 7 days.</small></label><div class="dialog-actions">${order.expectedReadyAt?`<button class="button ghost" data-action="estimate-clear" data-id="${e(order.id)}">Clear estimate</button>`:''}<button class="button outline" data-action="close-modal">Go back</button><button class="button" data-action="estimate-save" data-id="${e(order.id)}">Save estimate</button></div></div>`);
  }
  reorder(){
    const order=this.s.orders.find(item=>item.id===this.s.selected);
    if(!order||this.s.session?.admin||order.ownerId!==this.s.session?.uid||!terminal.includes(order.status)||order.documentDeletedAt)return;
    const prepare=()=>this.prepareReorder(order);
    if(this.s.draft.pdf)return this.confirm('Replace the unfinished draft?','The new draft will use this previous PDF and print options. You will review current prices before sending.','Prepare repeat order',prepare);
    return this.action(prepare);
  }
  async prepareReorder(order, correcting = false){
    const uid=this.s.session?.uid,generation=this.generation,key=this.draftKey;
    const bytes=await this.backend.bytes(order),file=new Blob([bytes],{type:'application/pdf'});file.name=order.fileName;
    const { inspectPdf } = await import('./pdf.mjs');
    const { readImageSize } = await import('./print-file.mjs');
    const pdf = { ...await inspectPdf(file), imagePercent: await readImageSize(file) };
    if(uid!==this.s.session?.uid||generation!==this.generation)return;
    const options=structuredClone(order.options);delete options.selectedPages;options.pages=pdf.pages;validateOptions(options);
    const revision = correcting ? { id: order.id, version: order.review.version, note: order.review.note } : null;
    const attachments = await Promise.all((order.attachments ?? []).map(async (item,index) => {
      const file = new Blob([await this.backend.bytes(order,index+1)],{type:"application/pdf"});
      file.name = item.fileName;
      const pdf = { ...await inspectPdf(file), imagePercent: await readImageSize(file) };
      if (pdf.pages !== item.options.pages) throw new Error("A saved PDF no longer matches its page count.");
      const options = structuredClone(item.options);
      validateOptions(options);
      return { pdf, file, options, rawCopies: String(options.copies) };
    }));
    if(uid!==this.s.session?.uid||generation!==this.generation)return;
    const draft={id:correcting ? order.id : crypto.randomUUID(),revision,addons:order.review?.addons,attachments,pdf,file,options,rawCopies:String(options.copies),rawRanges:rangeText(options.pageRanges),rangeError:null};
    this.s.draft=draft;this.s.draftWarning=null;this.s.requestError=null;
    const {file:blob,...value}=draft;
    try{await db.transact(['settings','files'],tx=>{tx.objectStore('settings').put({id:key,value});tx.objectStore('files').put({id:key,blob});});}
    catch{this.s.draftWarning='Your repeat order is ready, but a recovery copy could not be saved. Keep this tab open until you submit.';}
    this.s.route='request';history.replaceState(null,'','#request');
    this.toast(correcting ? 'Update the PDF or print options, then send your correction.' : 'Repeat draft ready. Review current prices before submitting.');
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
    (dialog.querySelector("[autofocus]") ?? dialog.querySelector("button,input,textarea,a"))?.focus();
  }
  closeModal() {
    if (this.preview) {
      this.preview.pages?.dispose();
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
  async previewPdf(name, load, options) {
    this.openModal(`<div class="preview-shell"><header class="preview-head"><h2 class="grow">${e(name)}</h2><button class="icon-button" data-action="close-modal" aria-label="Close PDF">${icon("close")}</button></header>${options ? `<div class="preview-settings"><p><strong>${e(paperLabel(options.paper))}</strong> · ${e(printScaleLabel(options))}</p><p class="small">Dashed lines show estimated printer margins.</p><p class="danger-text" id="preview-clipped" role="status" hidden></p><button class="text-action" data-action="preview-layout">View original PDF</button></div>` : ""}<div class="preview-pages" id="preview-pages" role="region" aria-label="Scrollable PDF pages" tabindex="0"><div class="spinner" aria-label="Loading PDF"></div></div><footer class="preview-footer"><button class="button outline" data-action="preview-prev" disabled>Previous</button><span id="page-label" class="small">Loading\u2026</span><button class="button outline" data-action="preview-next" disabled>Next</button><a id="pdf-download" class="icon-button hidden" download="${e(name)}" aria-label="Download PDF">${icon("download")}</a></footer></div>`, null, true);
    const current = { doc: null, page: options?.pageRanges?.[0]?.start ?? 1, url: null, pages: null, options, layout: !!options };
    this.preview = current;
    try {
      const bytes = await load();
      if (this.preview !== current) return;
      current.url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
      const a = document.getElementById("pdf-download");
      a.href = current.url;
      a.classList.remove("hidden");
      const { openPdf } = await import("./pdf.mjs");
      const doc = await openPdf(bytes);
      if (this.preview !== current) {
        await doc.destroy();
        return;
      }
      current.doc = doc;
      await this.showPreviewPages();
    } catch (error) {
      if (this.preview === current) {
        document.getElementById("preview-pages").innerHTML = `<div class="card stack"><h3>PDF preview unavailable</h3><p>${e(errorMessage(error))}</p><button class="button outline" data-action="close-modal">Close and try again</button></div>`;
      }
    }
  }
  async showPreviewPages() {
    const p = this.preview;
    if (!p?.doc) return;
    const { renderPage, renderPrintPage } = await import("./pdf.mjs");
    if (this.preview !== p) return;
    const area = document.getElementById("preview-pages");
    const label = document.getElementById("page-label");
    const note = document.getElementById("preview-clipped");
    const toggle = this.modal.dialog.querySelector('[data-action="preview-layout"]');
    const previous = this.modal.dialog.querySelector('[data-action="preview-prev"]');
    const next = this.modal.dialog.querySelector('[data-action="preview-next"]');
    const layout = p.layout;
    if (toggle) toggle.textContent = layout ? "View original PDF" : "View print layout";
    p.pages?.dispose();
    p.pages = mountPreviewPages(area, {
      pages: p.doc.numPages,
      initialPage: p.page,
      renderPage: async (page, canvas) => {
        if (layout) return renderPrintPage(p.doc, page, canvas, p.options);
        await renderPage(p.doc, page, canvas);
        return false;
      },
      onPage: (page, clipped) => {
        p.page = page;
        label.textContent = `${page} / ${p.doc.numPages}`;
        previous.disabled = page <= 1;
        next.disabled = page >= p.doc.numPages;
        if (note) {
          note.hidden = !clipped;
          note.textContent = clipped ? "Actual size may cut off content. Use Fit to paper to keep the whole page." : "";
        }
      }
    });
  }
  changePage(delta) {
    const p = this.preview;
    p?.pages?.jump(p.page + delta);
  }
  async registerWorker() {
    if (!("serviceWorker" in navigator) || location.protocol === "file:") return;
    try {
      await navigator.serviceWorker.register("/sw.js", { updateViaCache: "none" });
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
