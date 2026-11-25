import { attachmentEntries, sameAttachments, requireBatchRanges, requestOutOfStock, uploadRequestFiles } from "./attachment.mjs";
import { requiresPaperQuote } from "./paper.mjs";
import { newReview, canAccept, reviewUpdate } from "./review.mjs";
import { HostedDocuments } from "./documents.mjs";
import { initializeApp } from "firebase/app";
import { getAuth, onAuthStateChanged, getIdTokenResult, GoogleAuthProvider, signInWithPopup, signInWithEmailAndPassword, createUserWithEmailAndPassword, updateProfile, sendPasswordResetEmail, signOut, browserLocalPersistence, inMemoryPersistence, setPersistence } from "firebase/auth";
import { getFirestore, collection, query, where, orderBy, limit, onSnapshot, doc, getDocFromServer, setDoc, runTransaction } from "firebase/firestore";
import { getStorage, ref, getBytes } from "firebase/storage";
import { dayKey, canTransition, makeOrder, online, timeout, MAX_BYTES, validateOptions, defaultShop, validateShop, queueStatus, sameOptions, validPickupTime } from "./model.mjs";
class FirebaseBackend {
  constructor(config, change, documents = new HostedDocuments()) {
    this.documents = documents;
    this.app = initializeApp(config);
    this.auth = getAuth(this.app);
    this.db = getFirestore(this.app);
    this.storage = getStorage(this.app);
    this.change = change;
    this.mode = "live";
    this.session = null;
    this.generation = 0;
    this.orders = [];
    this.cache = false;
    this.error = null;
    this.settings = defaultShop();
    this.settingsError = null;
    this.queueStatus = null;
    this.reviewAvailable = false;
    this.paperSizesAvailable = false;
    this.printToolsAvailable = false;
  }
  async init() {
    try {
      await setPersistence(this.auth, browserLocalPersistence);
    } catch {
      await setPersistence(this.auth, inMemoryPersistence);
      this.persistenceWarning = "This browser cannot keep your session. You may need to sign in again after closing it.";
    }
    this.unAuth = onAuthStateChanged(this.auth, (user) => this.bind(user));
  }
  async bind(user) {
    const previous = { session: this.session, orders: this.orders, settings: this.settings }, generation = ++this.generation;
    this.unOrders?.();
    this.unSettings?.();
    this.unQueueStatus?.();
    this.unQueueSource?.();
    this.unOrders = null;
    this.unSettings = null;
    if (!user) {
      this.orders = [];
      this.session = null;
      this.settings = defaultShop();
      this.queueStatus = null;
      this.settingsError = null;
      this.error = null;
      this.cache = false;
      this.reviewAvailable = false;
      this.paperSizesAvailable = false;
      this.printToolsAvailable = false;
      this.emit();
      return;
    }
    const same = previous.session?.uid === user.uid;
    if (!same) {
      this.orders = [];
      this.session = null;
      this.settings = defaultShop();
      this.queueStatus = null;
      this.settingsError = null;
      this.error = null;
      this.cache = false;
      this.reviewAvailable = false;
      this.paperSizesAvailable = false;
      this.printToolsAvailable = false;
      this.change({ session: null, orders: [], ready: false, syncError: null });
    }
    try {
      const token = await timeout(getIdTokenResult(user, true));
      let consoleAdmin = false;
      if (token.claims.admin !== true) try {
        const role = await timeout(getDocFromServer(doc(this.db, "admins", user.uid)), 1e4);
        consoleAdmin = role.data()?.enabled === true;
      } catch {
      }
      if (generation !== this.generation) return;
      const isAdmin = token.claims.admin === true || consoleAdmin;
      if (!same || previous.session.admin !== isAdmin) this.orders = [];
      this.session = { uid: user.uid, name: user.displayName || user.email?.split("@")[0] || "Customer", email: user.email, admin: isAdmin, photoUrl: user.photoURL || null, provider: user.providerData?.some((p) => p.providerId === "google.com") ? "google" : "email" };
      this.emit();
      timeout(getDocFromServer(doc(this.db, "capabilities", "print-review-v1")), 10000).then(() => {
        if (generation === this.generation) {
          this.reviewAvailable = true;
          this.emit();
        }
      }).catch(() => {});
      timeout(getDocFromServer(doc(this.db, "capabilities", "paper-sizes-v1")), 10000).then(() => {
        if (generation === this.generation) {
          this.paperSizesAvailable = true;
          this.emit();
        }
      }).catch(() => {});
      timeout(getDocFromServer(doc(this.db, "capabilities", "print-tools-v1")), 10000).then(() => {
        if (generation === this.generation) {
          this.printToolsAvailable = true;
          this.emit();
        }
      }).catch(() => {});
      this.bindQueue(generation, isAdmin);
      this.unSettings = onSnapshot(doc(this.db, "shopSettings", "current"), { includeMetadataChanges: true }, (snapshot) => {
        if (generation !== this.generation) return;
        try {
          this.settings = validateShop(snapshot.exists() ? snapshot.data() : defaultShop());
          this.settingsError = null;
        } catch {
          this.settingsError = "The shop details could not be read. Contact the shop before submitting.";
        }
        this.emit();
      }, () => {
        if (generation !== this.generation) return;
        this.settingsError = "Order tracking is not ready. Your PDF can stay selected; contact the shop before sending.";
        this.emit();
      });
      let q = collection(this.db, "orders");
      q = this.session.admin ? query(q, orderBy("createdAt", "desc"), limit(500)) : query(q, where("ownerId", "==", user.uid), orderBy("createdAt", "desc"), limit(500));
      this.unOrders = onSnapshot(q, { includeMetadataChanges: true }, (snapshot) => {
        if (generation !== this.generation) return;
        this.orders = snapshot.docs.map((d) => ({ id: d.id, ...d.data() })).filter((o) => {
          try {
            validateOptions(o.options);
            return true;
          } catch {
            return false;
          }
        });
        this.cache = snapshot.metadata.fromCache;
        this.error = snapshot.size === 500 ? "Showing the latest 500 requests." : this.orders.length !== snapshot.size ? "Some saved requests could not be read. Contact the shop." : null;
        this.emit();
      }, () => {
        if (generation !== this.generation) return;
        this.error = "Orders could not sync. Check your connection, then check Orders again. If this continues, contact the shop.";
        this.emit();
      });
    } catch (error) {
      if (generation !== this.generation) return;
      if (same) {
        this.session = previous.session;
        this.orders = previous.orders;
        this.settings = previous.settings;
        this.cache = true;
        this.error = "Could not refresh. Showing saved orders; reconnect to sync.";
        this.emit();
      } else this.change({ session: null, orders: [], ready: true, syncError: "Could not restore the account. Check your connection and sign in again." });
    }
  }
  async bindQueue(generation, admin) {
    const target = doc(this.db, "queueStatus", "current");
    try { await timeout(getDocFromServer(target), 10000); }
    catch { return; }
    if (generation !== this.generation) return;
    this.unQueueStatus = onSnapshot(target, snapshot => {
      if (generation !== this.generation) return;
      this.queueStatus = snapshot.data() ?? null;
      this.emit();
    }, () => {});
    if (!admin) return;
    let lastKey, observedAt = 0;
    const source = query(collection(this.db, "orders"), where("status", "in", ["Accepted", "Printing"]));
    // Report only server-confirmed snapshots. Older observations cannot overwrite newer ones.
    this.unQueueSource = onSnapshot(source, { includeMetadataChanges: true }, snapshot => {
      if (generation !== this.generation || snapshot.metadata.fromCache || snapshot.metadata.hasPendingWrites) return;
      const next = queueStatus(snapshot.docs.map(item => item.data()), observedAt = Math.max(Date.now(), observedAt + 1));
      const key = next.waiting + ":" + next.printing;
      if (key === lastKey) return;
      lastKey = key;
      timeout(runTransaction(this.db, async tx => {
        const previous = (await tx.get(target)).data();
        if (generation === this.generation && (previous?.checkedAt ?? 0) < next.checkedAt) tx.set(target, next);
      })).catch(() => { if (lastKey === key) lastKey = null; });
    }, () => {});
  }
  emit() {
    this.change({ session: this.session, orders: this.orders, ready: true, cached: this.cache, settings: this.settings, settingsError: this.settingsError, queueStatus: this.queueStatus, reviewAvailable: this.reviewAvailable, paperSizesAvailable: this.paperSizesAvailable, printToolsAvailable: this.printToolsAvailable, syncError: this.error || this.persistenceWarning || null });
  }
  google() {
    online();
    const login = signInWithPopup(this.auth, new GoogleAuthProvider().setCustomParameters({ prompt: "select_account" }));
    return login.then(async (credential) => {
      await this.bind(credential.user);
      return credential;
    });
  }
  async email(email, password, name) {
    online();
    if (!name) {
      const result = await timeout(signInWithEmailAndPassword(this.auth, email.trim(), password));
      await this.bind(result.user);
      return result;
    }
    const credential = await timeout(createUserWithEmailAndPassword(this.auth, email.trim(), password));
    await timeout(updateProfile(credential.user, { displayName: name.trim() }));
    await this.bind(credential.user);
  }
  reset(email) {
    online();
    return timeout(sendPasswordResetEmail(this.auth, email.trim()));
  }
  async signOut() {
    this.unOrders?.();
    this.orders = [];
    this.error = null;
    this.cache = false;
    await signOut(this.auth);
  }
  refresh() {
    return this.bind(this.auth.currentUser);
  }
  async submit(draft, onProgress) {
    online();
    const session = this.session;
    if (!session) throw new Error("Sign in first.");
    requireBatchRanges(draft);
    this.checkPrintTools(draft);
    const attachments = attachmentEntries(draft.attachments);
    const specialPaper = requiresPaperQuote(draft.options.paper) || attachments.length > 0;
    if (specialPaper && !this.paperSizesAvailable) throw new Error("Expanded paper sizes and multi-PDF requests are unavailable. Refresh before submitting.");
    const review = (draft.addons != null && this.reviewAvailable) || specialPaper ? newReview(draft.addons ?? []) : null;
    if (draft.addons?.length && !review) throw new Error("Add-ons are unavailable. Refresh before submitting.");
    let order = makeOrder(draft.id, session, draft.pdf, draft.options, this.settings.rates);
    const orderRef = doc(this.db, "orders", order.id);
    const prior = await timeout(getDocFromServer(orderRef));
    if (prior.exists()) {
      const saved = prior.data();
      if (["Cancelled", "Rejected"].includes(saved.status)) throw new Error("This request was closed. Check Orders, then discard the draft to start a new request.");
      if (saved.documentStore === "hosted" && saved.fileHash !== draft.pdf.sha256 || !sameOptions(saved.options, draft.options) || JSON.stringify(saved.review?.addons || []) !== JSON.stringify(review?.addons || []) || !sameAttachments(saved.attachments, attachments)) throw new Error("This request was already submitted with an earlier document or options. Check Orders before starting a new request.");
      return order.id;
    }
    const shop = await timeout(getDocFromServer(doc(this.db, "shopSettings", "current")));
    const current = validateShop(shop.exists() ? shop.data() : defaultShop());
    if (!current.acceptingOrders) throw new Error("The shop has paused new requests. Your draft is saved.");
    if (current.version !== this.settings.version || JSON.stringify(current.rates) !== JSON.stringify(this.settings.rates)) throw new Error("Shop details or rates changed. Review the updated total and try again.");
    order = makeOrder(draft.id, session, draft.pdf, draft.options, current.rates);
    const user = this.auth.currentUser;
    if (!user || user.uid !== session.uid || this.session?.uid !== session.uid) throw new Error("Sign in again before sending your PDF.");
    if (review) order.review = review;
    if (attachments.length) order.attachments = attachments;
    if (specialPaper) order.estimatedCost = 0;
    Object.assign(order, await uploadRequestFiles(this.documents,user,draft,onProgress,review ? order.id : null));
    const { id, ...metadata } = order;
    await timeout(setDoc(orderRef, metadata));
    onProgress(1);
    return id;
  }
  async transition(id, next, note = "") {
    online();
    if (!this.session) throw new Error("Sign in first.");
    const session = this.session;
    await timeout(runTransaction(this.db, async (tx) => {
      const target = doc(this.db, "orders", id), snapshot = await tx.get(target);
      if (!snapshot.exists()) throw new Error("The order no longer exists.");
      const old = snapshot.data();
      if (!canTransition(old.status, next)) throw new Error("The order changed. Review its latest status.");
      if (!session.admin && !(session.uid === old.ownerId && next === "Cancelled")) throw new Error("You cannot update this order.");
      if (next === "Rejected" && !note.trim()) throw new Error("Give the customer a reason for rejecting the request.");
      const updates = { status: next, note: note.trim().slice(0, 500), updatedAt: Date.now() };
      if (next === "Completed") updates.completedAt = Date.now();
      if (next === "Accepted") {
        if (!canAccept(old)) throw new Error("The customer must approve the final price first.");
        const day = dayKey(), counter = doc(this.db, "counters", day), current = await tx.get(counter), number = (current.data()?.last ?? 0) + 1;
        tx.set(counter, { last: number, orderId: id });
        updates.queue = number;
        updates.queueDay = day;
      }
      tx.update(target, updates);
    }));
  }
  async reviewOrder(id, action, version, input = {}) {
    online();
    const session = this.session;
    const target = doc(this.db, "orders", id);
    const user = this.auth.currentUser;
    if (!session || user?.uid !== session.uid) throw new Error("Sign in to update this request.");
    let changes = {};
    if (action === "revise") {
      const prior = (await timeout(getDocFromServer(target))).data();
      const draft = input.draft;
      requireBatchRanges(draft);
      this.checkPrintTools(draft);
      const attachments = attachmentEntries(draft.attachments);
      if ((requiresPaperQuote(draft.options.paper) || attachments.length) && !this.paperSizesAvailable) throw new Error("Expanded paper sizes and multi-PDF requests are unavailable. Refresh before submitting.");
      // The transaction may have committed before the network acknowledged it.
      if (prior?.ownerId === session.uid && prior.review?.stage === "revised" &&
          prior.review.version === version + 1 && prior.fileHash === draft.pdf.sha256 &&
          prior.fileName === draft.pdf.name.slice(0, 255) && sameOptions(prior.options, draft.options) &&
          sameAttachments(prior.attachments, attachments)) return;
      reviewUpdate(prior, session, action, version);
      const settings = await timeout(getDocFromServer(doc(this.db, "shopSettings", "current")));
      const shop = validateShop(settings.exists() ? settings.data() : defaultShop());
      if (shop.version !== this.settings.version) throw new Error("Shop rates changed. Review the new estimate.");
      const { fileName, options, pricing, estimatedCost } = makeOrder(id, session, draft.pdf, draft.options, shop.rates);
      changes = { fileName, options, pricing, estimatedCost: requiresPaperQuote(options.paper) || attachments.length ? 0 : estimatedCost, attachments,
        ...await uploadRequestFiles(this.documents,user,draft,input.onProgress ?? (() => {}),id) };
    }
    await timeout(runTransaction(this.db, async tx => {
      if (this.auth.currentUser?.uid !== session.uid) throw new Error("Your account changed. Sign in again.");
      const order = (await tx.get(target)).data();
      const review = reviewUpdate(order, session, action, version, input);
      tx.update(target, { ...changes, review, updatedAt: Date.now() });
    }));
  }
  async cleanupPdf(id) {
    if (!this.session?.admin) throw new Error("Only the shop can remove an expired PDF.");
    return this.documents.cleanup(this.auth.currentUser, id);
  }
  checkPrintTools(draft) {
    const files = [draft, ...(draft.attachments ?? [])];
    if (!this.printToolsAvailable && files.some(file => file.options.actualSize)) throw new Error("Actual-size printing is unavailable. Choose Fit to paper or refresh.");
    if (requestOutOfStock(draft, this.settings)) throw new Error("A selected paper size is out of stock. Choose a different size.");
  }
  async markPrinted(id, index, checked) {
    online();
    if (!this.session?.admin || !this.printToolsAvailable) throw new Error("The print checklist is unavailable.");
    await timeout(runTransaction(this.db, async tx => {
      const target = doc(this.db, "orders", id), order = (await tx.get(target)).data();
      if (order?.status !== "Printing" || !Number.isInteger(index) || index < 0 || index > (order.attachments?.length ?? 0)) throw new Error("Refresh this request before updating its checklist.");
      const printed = new Set(order.printedFiles ?? []);
      if (checked) printed.add(index);
      else printed.delete(index);
      tx.update(target, { printedFiles: [...printed].sort(), updatedAt: Date.now() });
    }));
  }
  async setPickupTime(id, time) {
    online();
    if (!this.session?.admin) throw new Error("Only the shop can set a pickup estimate.");
    if (!validPickupTime(time)) throw new Error("Choose a pickup time within the next seven days.");
    await timeout(runTransaction(this.db, async (tx) => {
      const target = doc(this.db, "orders", id), snap = await tx.get(target);
      if (!snap.exists() || !["Pending", "Accepted", "Printing"].includes(snap.data().status)) throw new Error("This request no longer needs a pickup estimate.");
      tx.update(target, { expectedReadyAt: time, updatedAt: Date.now() });
    }));
  }
  async saveSettings(settings) {
    online();
    if (!this.session?.admin) throw new Error("Administrator access is required.");
    validateShop(settings);
    await timeout(runTransaction(this.db, async (tx) => {
      const target = doc(this.db, "shopSettings", "current"), snapshot = await tx.get(target), old = snapshot.exists() ? snapshot.data() : defaultShop();
      if (old.version !== settings.version) throw new Error("Shop details changed. Refresh and try again.");
      tx.set(target, { ...settings, version: old.version + 1 });
    }));
  }
  async bytes(order, index = 0) {
    online();
    if (order.documentDeletedAt) throw new Error("This PDF was removed under the shop\u2019s retention policy. Your order history is saved.");
    if (order.documentStore === "hosted") return this.documents.bytes(this.auth.currentUser, order, index);
    if (index) throw new Error("This PDF is unavailable.");
    try {
      return new Uint8Array(await timeout(getBytes(ref(this.storage, order.storagePath), MAX_BYTES), 6e4));
    } catch (e) {
      if (e.code === "storage/unknown") throw new Error("PDF preview could not load. Check the Storage bucket CORS setup and your connection.");
      throw e;
    }
  }
  close() {
    this.generation++;
    this.unAuth?.();
    this.unOrders?.();
    this.unSettings?.();
    this.unQueueStatus?.();
    this.unQueueSource?.();
  }
}
export {
  FirebaseBackend
};
