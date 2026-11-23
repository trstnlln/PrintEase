import { printFileTypes } from "./print-file.mjs";
import { MAX_ATTACHMENTS, batchRangesValid, requestOutOfStock } from "./attachment.mjs";
import { nextPickupWindow, inputTime, weekdays } from "./pickup-schedule.mjs";
import { standardPapers, quotedPapers, customDimensions, requiresPaperQuote, paperLabel, paperStock, stockPapers, stockLabels, printScaleLabel } from "./paper.mjs";
import { addons, canAccept, reviewLabel, shopQueueGroups, shopQueueGroup } from "./review.mjs";
import { escapeHtml as e, pesos, estimate, terminal, statuses, dateLabel, filterOrders, defaultShop, defaultRates, pageLabel, selectedPages, rangeText, orderTotal, collectionCode, pickupLabel } from "./model.mjs";
const paths = { home: "M3 10 12 3l9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z", orders: "M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6m-6 4h6", account: "M20 21v-2a7 7 0 0 0-14 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0", plus: "M12 5v14M5 12h14", right: "m9 5 7 7-7 7", back: "m15 5-7 7 7 7", close: "m6 6 12 12M6 18 18 6", search: "M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0", pdf: "M14 2H5v20h14V7Zm0 0v6h5M8 12h8m-8 4h6", upload: "M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6", check: "m5 12 4 4L19 6", layers: "m12 3 10 6-10 6L2 9Zm-8 11 8 5 8-5M4 18l8 5 8-5", ready: "M21 11v1a9 9 0 1 1-5-8m-8 7 4 4L22 5", refresh: "M20 7a9 9 0 1 0 1 9M20 3v5h-5", logout: "M9 3H3v18h6m5-14 5 5-5 5m-7-5h15", info: "M12 10v7m0-11v1M22 12A10 10 0 1 1 2 12a10 10 0 0 1 20 0", wifi: "M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-11 4a6 6 0 0 1 8 0m-4 4h.01", clock: "M12 7v5l3 2M22 12A10 10 0 1 1 2 12a10 10 0 0 1 20 0", store: "M3 9h18l-2-6H5Zm1 0v12h16V9M9 21v-7h6v7", download: "M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4", eye: "M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0", share: "M12 15V2m-4 4 4-4 4 4M7 9H4v13h16V9h-3", phone: "M7 2h10v20H7Zm3 17h4", empty: "M3 4h18v16H3Zm0 9h5l2 3h4l2-3h5" };
const icon = (name, cls = "") => `<svg class="icon ${cls}" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="${paths[name] || paths.info}"/></svg>`;
const brand = () => '<a class="brand" href="#home" aria-label="PrintEase home"><img src="/assets/logo.png" alt=""><span>PrintEase</span></a>';
const badge = (status) => `<span class="badge status" data-status="${e(status)}">${e(status)}</span>`;
const button = (label, action, options = {}) => `<button class="button ${options.cls || ""}" data-action="${action}" ${options.disabled ? "disabled" : ""}>${options.icon ? icon(options.icon) : ""}${e(label)}</button>`;
function authView(s) {
  const demo = s.mode === "demo";
  return `<div class="auth"><section class="auth-story">${brand()}<div><div class="eyebrow">Made for your campus</div><h1 class="headline">A little less queue.<br><span>A little more you.</span></h1><p class="lead">Your documents, printed on your schedule. Submit from anywhere. Pick up when it\u2019s ready.</p><div class="auth-art" aria-hidden="true"><div class="paper-preview"><div class="row between"><span class="pdf-icon">${icon("pdf")}</span><span class="badge">PDF \xB7 5 MB max</span></div><h3 style="margin-top:16px">Your next great idea.pdf</h3><div class="paper-line"></div><div class="paper-line"></div><div class="paper-line short"></div><div class="row between" style="margin-top:22px"><span class="small">From upload to pickup</span>${icon("right")}</div></div><div class="ready-float"><div class="row">${icon("ready")}<div><b>Printed, without the wait.</b><div class="small">Make room for your next class.</div></div></div></div></div></div><p class="small">Print online \xB7 Collect near BSU Alangilan</p></section><main id="main" class="auth-main"><div class="auth-form stack"><div class="auth-mobile-intro" style="display:none">${brand()}<div class="eyebrow">Your campus. Your time.</div><h1>Great prints.<br>Less waiting.</h1><p class="muted">Submit your PDF, follow the queue, and pick up when it\u2019s ready.</p></div><div><span class="badge">${demo ? "LOCAL DEMO" : "PRINTEASE FOR THE WEB"}</span><h2>${demo ? "Try a smoother print day." : s.auth.register ? "Create your student account" : "Welcome back."}</h2><p class="small">${demo ? "Explore both sides of the print shop. Demo orders stay in this browser." : "Your nearby print shop, wherever you are."}</p></div>${s.syncError ? `<div class="notice error">${icon("info")}<span>${e(s.syncError)}</span></div>` : ""}${demo ? `<div class="card stack">${button("Continue as student", "demo-student", { icon: "account", disabled: s.busy })}${button("Explore the shop workspace", "demo-admin", { icon: "store", cls: "outline", disabled: s.busy })}<small>No real accounts or cloud uploads. Files are saved only in this browser.</small></div>${button("Back to Google sign-in", "use-live", { cls: "ghost", disabled: s.busy })}` : `<div class="card stack"><button class="button google full" data-action="google" ${s.busy || !s.ready ? "disabled" : ""}><svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="#4285F4" d="M21.6 12.23c0-.71-.06-1.39-.18-2.05H12v3.88h5.38c-.23 1.25-.94 2.31-2 3.02v2.51h3.24c1.9-1.75 2.98-4.32 2.98-7.36z"/><path fill="#34A853" d="M12 22c2.7 0 4.96-.9 6.62-2.42l-3.24-2.51c-.9.6-2.05.96-3.38.96-2.6 0-4.8-1.76-5.59-4.12H3.07v2.59C4.72 19.76 8.1 22 12 22z"/><path fill="#FBBC05" d="M6.41 13.91a6 6 0 0 1 0-3.82V7.5H3.07a10 10 0 0 0 0 9z"/><path fill="#EA4335" d="M12 5.97c1.47 0 2.79.51 3.83 1.51l2.87-2.87C16.96 3 14.7 2 12 2a10 10 0 0 0-8.93 5.5l3.34 2.59C7.2 7.73 9.4 5.97 12 5.97z"/></svg>Continue with Google</button><div class="divider">or use email</div><form id="auth-form" class="form">${s.auth.error ? `<div class="notice error auth-error" role="alert">${icon("info")}<span>${e(s.auth.error)}</span></div>` : ""}${s.auth.register ? `<label>Full name<input id="auth-name" name="name" autocomplete="name" minlength="2" maxlength="200" value="${e(s.auth.name)}" required ${s.busy ? "disabled" : ""}></label>` : ""}<label>Email address<input id="auth-email" name="email" autocomplete="email" type="email" value="${e(s.auth.email)}" placeholder="you@example.com" required ${s.busy ? "disabled" : ""}></label><label>Password<span class="pass-field"><input id="auth-password" name="password" autocomplete="${s.auth.register ? "new-password" : "current-password"}" type="${s.auth.visible ? "text" : "password"}" value="${e(s.auth.password)}" ${s.auth.register ? 'minlength="8"' : ""} required ${s.busy ? "disabled" : ""}><button type="button" data-action="toggle-password" aria-label="${s.auth.visible ? "Hide" : "Show"} password">${icon("eye")}</button></span>${s.auth.register ? "<small>At least 8 characters</small>" : ""}</label><button class="button full" type="submit" ${s.busy || !s.ready ? "disabled" : ""}>${s.busy ? "Please wait\u2026" : !s.ready ? "Connecting\u2026" : s.auth.register ? "Create account" : "Sign in"}</button></form>${!s.auth.register ? '<button class="inline-link" data-action="reset-password">Reset password</button>' : ""}<button class="inline-link" data-action="toggle-auth">${s.auth.register ? "Already registered? Sign in" : "New here? Create an account"}</button></div><button class="text-action" data-action="use-demo">Explore the demo first ${icon("right")}</button>`}<p class="small">${icon("phone")} Works in Safari. <button class="inline-link" data-action="install">Add PrintEase to your Home Screen</button></p></div></main></div>`;
}
function avatar(session, large = false) {
  let photo = null;
  try {
    const url = new URL(session.photoUrl);
    if (url.protocol === "https:" && !url.username && !url.password && (!url.port || url.port === "443") && (url.hostname === "googleusercontent.com" || url.hostname.endsWith(".googleusercontent.com") || url.hostname.endsWith(".ggpht.com"))) photo = url.href;
  } catch {
  }
  const initial = e(session.name?.trim()?.[0]?.toUpperCase() || "P");
  return `<${large ? "div" : "a"} class="${large ? "profile-avatar" : "avatar"} user-avatar" ${large ? `role="img" aria-label="Profile photo for ${e(session.name)}"` : `href="#account" aria-label="Open profile for ${e(session.name)}"`}><span class="avatar-initial" aria-hidden="true">${initial}</span>${photo ? `<img class="profile-photo" src="${e(photo)}" alt="" referrerpolicy="no-referrer" loading="lazy">` : ""}</${large ? "div" : "a"}>`;
}
function shell(s, content) {
  const session = s.session;
  const nav = [["home", "Home", "home"], ["orders", session.admin ? "Queue" : "Orders", "orders"], ["account", "Account", "account"]];
  const active = ["request", "detail"].includes(s.route) ? "orders" : s.route;
  const name = session.name.split(" ")[0];
  return `<div class="shell"><aside class="sidebar">${brand()}<div class="eyebrow">${session.admin ? "Shop workspace" : "Your print desk"}</div><nav class="nav-items" aria-label="Main navigation">${nav.map(([r, label, ic]) => `<a class="nav-item ${active === r ? "active" : ""}" href="#${r}" ${active === r ? 'aria-current="page"' : ""}>${icon(ic)}${label}</a>`).join("")}</nav><div class="sidebar-bottom"><div class="shop-note">${icon("store")}<b class="compact-label">${e((s.settings || defaultShop()).name)}</b><small>Near BSU Alangilan<br>Payment upon pickup</small></div><div class="row">${avatar(session)}<div class="grow"><b class="compact-label">${e(session.name)}</b><div class="small">${session.admin ? "Administrator" : "Student"}</div></div><button class="icon-button" data-action="sign-out" aria-label="Sign out" ${s.busy ? "disabled" : ""}>${icon("logout")}</button></div></div></aside><div class="workspace"><header class="topbar">${brand()}<div class="breadcrumb">Your workspace ${icon("right")} <b>${s.route === "detail" ? "Order details" : s.route === "request" ? "New print request" : nav.find((n) => n[0] === s.route)?.[1] || "Home"}</b></div><div class="top-actions">${s.mode === "demo" ? '<span class="badge">DEMO</span>' : `<span class="small row online-label"><span class="dot ${s.online ? "" : "offline-dot"}"></span>${s.online ? "Connected" : "Offline"}</span>`}<button class="icon-button" data-action="refresh" aria-label="Refresh orders" ${s.busy ? "disabled" : ""}>${icon("refresh")}</button>${avatar(session)}<button class="icon-button" data-action="sign-out" aria-label="Sign out" ${s.busy ? "disabled" : ""}>${icon("logout")}</button></div></header><main id="main" class="main ${s.route === "request" ? "request-page" : ""}" tabindex="-1">${content}</main></div>${["home", "orders", "account"].includes(s.route) ? `<nav class="bottom-nav" aria-label="Mobile navigation">${nav.map(([r, label, ic]) => `<a href="#${r}" class="${active === r ? "active" : ""}" ${active === r ? 'aria-current="page"' : ""}><span class="icon-wrap">${icon(ic)}</span>${label}</a>`).join("")}</nav>` : ""}</div>`;
}
function orderCard(o, admin) {
  return `<button class="order-card" data-action="order" data-id="${e(o.id)}"><span class="file-main"><span class="pdf-icon">${icon("pdf")}</span><span class="grow"><span class="file-title">${e(o.fileName)}</span><span class="file-sub">${admin ? e(o.studentName) : `${o.options.pages} pages \xB7 ${o.options.copies} copies \xB7 ${e(paperLabel(o.options.paper))}`}<br>${o.queue ? `Queue #${String(o.queue).padStart(3, "0")} \xB7 ${e(o.queueDay)}` : `Submitted ${dateLabel(o.createdAt)}`}</span></span></span><span class="status-cell">${badge(reviewLabel(o))}</span><span class="order-amount">${o.review?.quote != null ? pesos(o.review.quote) : (requiresPaperQuote(o.options.paper) || o.attachments?.length) ? "Shop quote" : pesos(orderTotal(o))}</span>${icon("right", "chevron")}</button>`;
}
function ordersList(s) {
  const filtered = filterOrders(s.orders, { filter: s.filter, search: s.search, admin: s.session.admin });
  if (filtered.length && s.session.admin && s.filter === "All") {
    return shopQueueGroups.map(group => {
      const orders = filtered.filter(order => shopQueueGroup(order) === group);
      if (!orders.length) return "";
      return `<section class="queue-group" aria-label="${group}"><div class="queue-group-heading"><h3>${group}</h3><span class="badge">${orders.length}</span></div>
        ${orders.map(order => orderCard(order, true)).join("")}
      </section>`;
    }).join("");
  }
  return filtered.length ? filtered.map((o) => orderCard(o, s.session.admin)).join("") : `<div class="card empty">${icon("empty")}<h3>${s.orders.length ? "No matching requests." : "Your queue starts here."}</h3><p>${s.orders.length ? "Try another search or choose a different filter." : "Send your first PDF and follow it from submission to pickup."}</p>${!s.session.admin && !s.orders.length ? button("New print request", "new-request", { icon: "plus" }) : ""}</div>`;
}
function dashboard(s) {
  const needsResponse = !s.session.admin && s.orders.filter(order => order.status === "Pending" && ["quoted", "correction"].includes(order.review?.stage));
  const admin = s.session.admin, overview = s.route === "home", active = s.orders.filter((o) => !terminal.includes(o.status)), ready = s.orders.filter((o) => o.status === "Ready for Pickup"), pending = s.orders.filter((o) => o.status === "Pending"), current = ready[0] || active.find((o) => o.queue) || active[0];
  return `${!admin && !overview ? submissionBanner(s) : ""}<div class="page-title"><div><div class="eyebrow">${admin ? e((s.settings || defaultShop()).name) : "Near BSU Alangilan"}</div><h1>${admin ? "A clear queue. A smoother day." : `Hello, ${e(s.session.name.split(" ")[0])}.`}</h1><p class="muted">${admin ? "Review requests and keep everyone in the loop." : "Make time for what\u2019s next. We\u2019ll handle the prints."}</p></div>${!admin && !overview ? button("New print request", "new-request", { icon: "plus" }) : ""}</div>${s.syncError || s.settingsError || s.cached || !s.online ? `<div class="notice">${icon("wifi")}<span>${e(s.syncError || s.settingsError || (!s.online ? "You\u2019re offline. Drafts and the local demo stay available. Connect to submit or update live orders." : "Showing cached orders. New updates will appear when connected."))}</span></div>` : ""}${overview ? `${!admin ? `<section class="hero-grid"><div class="card hero"><div class="hero-copy">${icon("pdf", "big")}<h2 style="margin-top:14px">Your next print,<br>just a tap away.</h2><p>Send a document, photo, or scan. Collect at the shop near campus.</p>${button(s.draft?.pdf ? "Continue your draft" : "New print request", "new-request", { cls: "lime", icon: "plus", disabled: !(s.settings || defaultShop()).acceptingOrders })}</div><div class="hero-graphic" aria-hidden="true"><div class="paper-line"></div><div class="paper-line"></div><div class="paper-line short"></div></div></div><div class="card pickup-card"><div><div class="row between"><span class="eyebrow">${current ? "Your latest progress" : "Ready when you are"}</span>${icon("clock")}</div><div class="queue">${current?.queue ? `#${String(current.queue).padStart(3, "0")}` : current ? "In review" : "From PDF to pickup"}</div><p class="file-small muted">${current ? e(current.fileName) : "Submit online and collect at the nearby print shop."}</p></div><div>${current ? `${badge(reviewLabel(current))}<br><button class="text-action" data-action="order" data-id="${e(current.id)}" style="margin-top:10px">View request ${icon("right")}</button>` : "<small>PDF, JPG, PNG \xB7 Up to 5 MB<br>Payment at pickup</small>"}</div></div></section>` : ""}${shopCard(s)}<section class="grid stats" aria-label="Order overview">${[[admin ? "To review" : "In progress", admin ? pending.length : active.length - ready.length, "layers"], ["Ready to collect", ready.length, "ready"], ["Completed", s.orders.filter((o) => o.status === "Completed").length, "check"]].map(([label, value, ic]) => `<div class="card stat"><div class="stat-icon">${icon(ic)}</div><div><div class="count">${value}</div><small>${label}</small></div></div>`).join("")}</section>` : ""}${overview && needsResponse?.length ? `<section class="card review-panel needs-response stack">
  <h2>Requests that need you</h2>
  ${needsResponse.slice(0, 4).map(order => `<button class="order-card" data-action="order" data-id="${e(order.id)}"><span class="file-title">${e(order.fileName)}</span><span class="badge">${e(reviewLabel(order))}</span></button>`).join("")}
  </section>` : ""}<section><div class="section-head"><div><h2>${admin ? "Print queue" : "Your orders"}</h2><p>${admin ? "All your requests, in one organized place." : "A clear view from upload to pickup."}</p></div>${!admin && overview ? '<a class="text-action" href="#orders">View all ' + icon("right") + "</a>" : ""}</div><div class="list-toolbar"><div class="filters" aria-label="Filter orders">${["All", ...admin ? ["Pending"] : [], "Active", "Ready", "History"].map((f) => `<button class="filter ${s.filter === f || f === "Ready" && s.filter === "Ready for Pickup" ? "active" : ""}" data-action="filter" data-filter="${f === "Ready" ? "Ready for Pickup" : f}" aria-pressed="${s.filter === f || f === "Ready" && s.filter === "Ready for Pickup"}">${f}</button>`).join("")}</div><label class="search"><span class="sr-only">Search orders</span>${icon("search")}<input id="order-search" type="search" placeholder="${admin ? "Search document, student or queue" : "Search your documents"}" value="${e(s.search)}"></label></div><div class="orders-list" id="orders-list">${ordersList(s)}</div></section><p class="footnote">${icon("info")}<span>${s.mode === "demo" ? "Demo workspace \xB7 Orders and PDFs are saved only in this browser." : "Pay at the shop when you collect your prints. Queue numbers identify requests; they are not wait-time estimates."}</span></p>`;
}
function submissionBanner(s) {
  if (!s.submitted) return "";
  const { id, revision } = s.submitted;
  const tracked = s.orders.find(order => order.id === id);
  return `<section class="card submission-banner" role="status"><span class="upload-icon">${icon("check")}</span>
    <div class="grow"><h2>${revision ? "Correction sent" : "Request submitted"}</h2>
    <p class="text-wrap small">Request ID ${e(id)}</p><p>${tracked?.queue ? "Follow this request in Orders." : "The shop will review your files and assign a queue number."}</p></div>
    ${tracked ? `<button class="button outline" data-action="order" data-id="${e(id)}">View request</button>` : ""}</section>`;
}

function pickupWindowCard(shop) {
  const next = nextPickupWindow(shop.pickupSchedule);
  if (!next) return "";
  return `<div class="pickup-estimate"><span class="estimate-symbol">${icon("clock")}</span><div>
    <b>Next pickup window</b><p>${e(next.window)}</p>
    <p class="small">${next.cutoff ? `Same-day request cutoff ${e(next.cutoff)}. ` : ""}Philippine time. The shop confirms when your order is ready.</p>
  </div></div>`;
}

function requestView(s) {
  const d = s.draft, o = d.options, shop = s.settings || defaultShop(), valid = Number.isInteger(Number(d.rawCopies)) && Number(d.rawCopies) >= 1 && Number(d.rawCopies) <= 100 && d.rawCopies.trim() !== "";
  const cost = estimate(o, shop.rates);
  const allowed = (shop.acceptingOrders || d.revision) && !s.settingsError && !d.rangeError && !d.paperError && (d.attachments || []).every(attachmentCopiesValid) && batchRangesValid(d) && !requestOutOfStock(d, shop);
  const quoteOnly = requiresPaperQuote(o.paper) || !!d.attachments?.length;
  const priceText = d.pdf && valid && allowed ? (quoteOnly ? "Shop quote" : pesos(cost.total)) : "—";
  return `<div class="page-title"><div><button class="text-action" data-action="back">${icon("back")} Back to orders</button><h1>${d.revision ? "Correct your request" : "New print request"}</h1><p class="muted">${d.revision ? e(d.revision.note) : "Add your files, choose how they print, and review your request."}</p></div></div>${pickupWindowCard(shop)}${s.requestError ? `<div class="notice error request-error" role="alert">${icon("info")}<span>${e(s.requestError)}</span></div>` : ""}${s.draftWarning ? `<div class="notice draft-warning" role="status">${icon("info")}<span>${e(s.draftWarning)}</span></div>` : ""}<div class="request-layout"><div><section class="step"><div class="step-head"><span class="step-num">1</span>Print files</div><div class="card upload ${d.pdf ? "selected" : ""}">${s.fileReading ? `<p class="file-status" role="status">Preparing your file\u2026</p>` : d.pdf ? `<p class="file-status" role="status">File ready to preview.</p>` : ""}<span class="upload-icon">${icon(d.pdf ? "pdf" : "upload", "big")}</span><h3>${d.pdf ? e(d.pdf.name) : "Add a document or image"}</h3><p class="small">${d.pdf ? `${d.pdf.pages} pages \xB7 ${Math.ceil(d.pdf.bytes / 1024)} KB \xB7 ${d.pdf.sourceKind ? `${d.pdf.sourceKind} converted to PDF` : "PDF"}` : "PDF, JPG, or PNG \xB7 Up to 5 MB per file"}</p><label class="button outline file-choose">${icon("plus")}${d.pdf ? "Replace file" : "Choose a file"}<input id="pdf-input" type="file" accept="${printFileTypes}" aria-label="Choose PDF, JPG, or PNG" ${s.busy ? "disabled" : ""}></label>${d.pdf ? "" : '<p class="file-help small">Export Word, PowerPoint, or Excel as PDF before uploading.</p>'}</div></section><section class="step"><div class="step-head"><span class="step-num">2</span>Print options</div><div class="card options-grid"><label>Pages to print<input id="page-ranges" type="text" inputmode="text" maxlength="120" placeholder="All pages" value="${e(d.rawRanges ?? rangeText(o.pageRanges))}" aria-describedby="range-help" aria-invalid="${!!d.rangeError}" ${s.busy || !d.pdf ? "disabled" : ""}><small id="range-help" class="${d.rangeError || !batchRangesValid(d) ? "danger-text" : ""}">${e(d.rangeError || (!batchRangesValid(d) ? "Multi-PDF requests print all pages. Clear the page ranges or submit this PDF separately." : "Leave blank for all pages, or enter 1-3, 5, 8-10."))}</small></label>${paperPicker(s)}${layoutControl(s)}<label>Number of copies<input id="copies" type="number" inputmode="numeric" min="1" max="100" step="1" value="${e(d.rawCopies)}" aria-describedby="copy-help" ${s.busy ? "disabled" : ""}><small id="copy-help" class="${valid ? "" : "danger-text"}">${valid ? "Choose 1\u2013100 copies." : "Enter a whole number from 1 to 100."}</small></label><hr><label class="switch-row"><span>Print in color<small>Off for black & white</small></span><input id="color" type="checkbox" ${o.color ? "checked" : ""} ${s.busy ? "disabled" : ""}></label><label class="switch-row"><span>Double-sided<small>Use both sides to save paper</small></span><input id="duplex" type="checkbox" ${o.duplex ? "checked" : ""} ${s.busy ? "disabled" : ""}></label></div></section>${s.paperSizesAvailable && d.pdf ? attachmentPicker(d, s) : ""}${s.reviewAvailable && !d.revision ? addonPicker(d, s.busy) : ""}${d.pdf ? button("Discard this draft", "discard", { cls: "ghost", disabled: s.busy }) : ""}</div><aside class="card estimate stack" aria-label="Order estimate"><div><h2>Print summary</h2></div><div><div class="price-line"><span>Document</span><b>${d.pdf ? `${d.pdf.pages} pages` : "Not selected"}</b></div><div class="price-line"><span>Paper</span><b id="summary-paper">${e(paperLabel(o.paper))}</b></div><div class="price-line"><span>Copies</span><b id="summary-copies">${valid ? o.copies : "\u2014"}</b></div><div class="price-line"><span>Print mode</span><b id="summary-mode">${o.color ? "Color" : "Black & white"}</b></div><div class="price-line"><span>Sides</span><b id="summary-sides">${o.duplex ? "Double-sided" : "Single-sided"}</b></div><div class="price-line"><span>Page sizing</span><b id="summary-scale">${printScaleLabel(o)}</b></div><hr><div class="small" id="summary-price-label">${quoteOnly ? "Pricing" : "Estimated total"}</div><div class="total" id="summary-total">${priceText}</div><small id="summary-formula">${quoteOnly ? "The shop checks your files and sends one price for approval." : d.pdf && valid ? `${selectedPages(o)} selected pages \xD7 ${o.copies} copies \xD7 ${pesos(cost.rate)} per printed side<br>${cost.sheets} sheets \xB7 Payment at pickup` : "Choose a document to calculate your estimate."}</small></div><div class="notice" style="margin:0">${icon("info")}<span>${quoteOnly ? "The shop will quote the full request after reviewing your files and paper choices." :
    (shop.ratesConfirmed ? "Shop-confirmed rates." : "Sample estimate. The shop confirms the final price.") + " Duplex uses fewer sheets. The per-side charge stays the same."}</span></div>${s.busy ? `<div><div class="progress"><span style="width:${Math.round(s.progress * 100)}%"></span></div><small id="progress-text" aria-live="polite">${s.progress >= 1 ? "Saving your request\u2026" : "Preparing or uploading your document\u2026"}</small></div>` : ""}${!shop.acceptingOrders || s.settingsError ? `<div class="notice error">${icon("info")}<span>${e(s.settingsError || "The shop has paused new requests. Your draft is saved.")}</span></div>` : ""}<div class="desktop-submit">${button(s.busy ? "Please wait\u2026" : "Review request", "review", { cls: "full", icon: "right", disabled: !d.pdf || !valid || !allowed || s.busy })}</div></aside></div><div class="mobile-submit"><div class="row"><div style="min-width:94px"><div class="small">${quoteOnly ? "Pricing" : "Estimated total"}</div><strong id="mobile-total">${priceText}</strong></div>${button(s.busy ? "Please wait\u2026" : "Review request", "review", { icon: "right", disabled: !d.pdf || !valid || !allowed || s.busy })}</div></div>`;
}
function paperPicker(s) {
  const draft = s.draft;
  const current = draft.options.paper;
  const size = customDimensions(current);
  const custom = !!size;
  const disabled = s.busy ? "disabled" : "";
  const options = list => list.map(paper => paperOption(paper, current, s)).join("");
  return `<div class="paper-picker stack">
    <label for="paper-size">Paper size</label>
    <select id="paper-size" name="paper" ${disabled}>
      <optgroup label="Standard sizes">${options(standardPapers)}</optgroup>
      ${s.paperSizesAvailable || requiresPaperQuote(current) ? `<optgroup label="Ask the shop for a quote">
        ${options(quotedPapers)}${paperOption("Custom", custom ? "Custom" : current, s)}
      </optgroup>` : ""}
    </select>
    ${custom ? `<div class="paper-dimensions"><label>Width (mm)<input id="paper-width" type="number" inputmode="numeric"
      min="50" max="1000" step="1" value="${e(draft.paperWidth ?? size[0])}" ${disabled}></label>
      <label>Height (mm)<input id="paper-height" type="number" inputmode="numeric" min="50" max="1000" step="1"
      value="${e(draft.paperHeight ?? size[1])}" ${disabled}></label></div>
      <small id="paper-dimension-help" class="${draft.paperError ? "danger-text" : ""}">${e(draft.paperError || "Enter whole millimeters from 50 to 1,000.")}</small>` : ""}
    ${stockNote(s, current)}
    ${requiresPaperQuote(current) ? '<p class="small">The shop will confirm the final price.</p>' : ""}
  </div>`;
}

function paperOption(paper, current, s) {
  const state = paperStock(s.settings, paper);
  return `<option value="${paper}" ${paper === current ? "selected" : ""} ${state === "out" && paper !== current ? "disabled" : ""}>${paper === "Custom" ? "Custom size" : paper}${s.printToolsAvailable ? " · " + stockLabels[state] : ""}</option>`;
}

function stockNote(s, paper) {
  if (!s.printToolsAvailable) return "";
  const state = paperStock(s.settings, paper);
  return `<p class="paper-stock ${state === "out" ? "danger-text" : "small"}">${state === "out" ? "Out of stock. Choose another paper size." : stockLabels[state]}</p>`;
}

function scaleOptions(options) {
  return `<option value="fit" ${options.actualSize ? "" : "selected"}>Fit to paper</option>
    <option value="actual" ${options.actualSize ? "selected" : ""}>Actual size</option>`;
}

function layoutControl(s) {
  const d = s.draft;
  return `<div class="layout-control">
    ${s.printToolsAvailable ? `<label>Page sizing<select id="scale" ${s.busy ? "disabled" : ""}>${scaleOptions(d.options)}</select></label>` : ""}
    ${d.pdf ? button("Preview print layout", "draft-preview", { cls: "outline", icon: "eye", disabled: s.busy || !!d.paperError }) : ""}
    ${s.printToolsAvailable ? '<small>Fit shrinks oversized pages. Actual size can crop at the margins.</small>' : ""}
  </div>`;
}

function attachmentCopiesValid(item) {
  const raw = item.rawCopies ?? String(item.options.copies);
  return raw !== "" && Number(raw) === item.options.copies;
}

function attachmentPicker(draft, s) {
  const busy = s.busy;
  const attachments = draft.attachments ?? [];
  const disabled = busy ? "disabled" : "";
  return `<section class="step"><div class="card stack extra-files">
    <h2>Additional files</h2>
    <p class="small">Add up to two more documents or images. Each file can have its own settings. The shop sends one quote for the request.</p>
    ${attachments.map((item, index) => {
      const field = key => `id="file-${index}-${key}" data-attachment-index="${index}" data-attachment-field="${key}" ${disabled}`;
      const select = [...standardPapers, ...quotedPapers];
      return `<div class="extra-file stack">
        <div class="row between"><h3 class="text-wrap">${e(item.pdf.name)}</h3><button class="text-action" data-action="remove-attachment" data-index="${index}" ${disabled}>Remove</button></div>
        <p class="small">${item.pdf.pages} pages · All pages</p>
        <div class="paper-dimensions"><label>Paper size<select ${field("paper")}>${select.map(paper => paperOption(paper, item.options.paper, s)).join("")}</select></label>
        <label>Copies<input ${field("copies")} type="number" inputmode="numeric" min="1" max="100" value="${e(item.rawCopies ?? item.options.copies)}"></label></div>
        ${stockNote(s, item.options.paper)}
        ${s.printToolsAvailable ? `<label>Page sizing<select ${field("actualSize")}>${scaleOptions(item.options)}</select></label>` : ""}
        <button class="text-action" data-action="draft-attachment-preview" data-index="${index}" ${disabled}>${icon("eye")} Preview print layout</button>
        <label class="switch-row"><span>Print in color</span><input ${field("color")} type="checkbox" ${item.options.color ? "checked" : ""}></label>
        <label class="switch-row"><span>Double-sided</span><input ${field("duplex")} type="checkbox" ${item.options.duplex ? "checked" : ""}></label>
      </div>`;
    }).join("")}
    ${attachments.length < MAX_ATTACHMENTS ? `<label class="button outline file-choose">Add another file<input id="attachment-input" type="file" accept="${printFileTypes}" aria-label="Add another PDF, JPG, or PNG" ${disabled}></label>` : ""}
  </div></section>`;
}

function addonPicker(draft, busy) {
  return `<fieldset class="card print-addons stack">
    <legend>Optional add-ons</legend>
    <p class="small">Ask the shop to include these in your final quote.</p>
    ${addons.map(({ id, label }) => `<label class="switch-row"><span>${label}</span><input type="checkbox"
      id="addon-${id}" data-addon="${id}" ${draft.addons?.includes(id) ? "checked" : ""} ${busy ? "disabled" : ""}></label>`).join("")}
  </fieldset>`;
}

function orderReviewPanel(order, admin, available, disabled) {
  const review = order.review;
  if (!review && (!admin || !available || order.status !== "Pending")) return "";
  const pending = order.status === "Pending";
  const stage = review?.stage ?? "submitted";
  const requested = addons.filter(item => review?.addons.includes(item.id)).map(item => item.label);
  const headings = {
    submitted: "Shop review",
    revised: "Correction received",
    correction: "Correction requested",
    quoted: "Final quote",
    approved: "Approved price",
    declined: "Quote declined",
  };
  const message = {
    submitted: admin ? "Review the PDF and add-ons, then send a final price." : "The shop will review your request and send a final price.",
    revised: admin ? "Review the student’s updated PDF and options before quoting." : "The shop will review your updated request.",
    quoted: admin ? "Waiting for the student to approve this price." : "Approve this price so the shop can accept your request.",
    approved: admin ? "The student approved this price. You can accept the request." : "You’ll pay this amount when you collect your prints.",
    declined: admin ? "The student declined the quote. You can send a new price." : "The shop can send another quote.",
  };
  const staffCanQuote = pending && admin && available && !["approved", "correction"].includes(stage);
  const staffCanCorrect = pending && admin && available && stage !== "correction";
  return `<section class="card review-panel stack ${pending && !admin && ["quoted","correction"].includes(stage) ? "needs-response" : ""}">
    <h2>${headings[stage] ?? "Shop review"}</h2>
    ${review?.quote != null ? `<div class="quote-amount">${pesos(review.quote)}</div>` : ""}
    ${requested.length ? `<p class="small">Requested add-ons · ${e(requested.join(", "))}</p>` : ""}
    ${review?.note ? `<p class="review-note">${e(review.note)}</p>` : ""}
    ${pending && message[stage] ? `<p class="small">${message[stage]}</p>` : ""}
    ${staffCanQuote ? button(stage === "quoted" ? "Update quote" : "Send final price", "final-quote", { disabled }) : ""}
    ${staffCanCorrect ? button("Request a correction", "request-correction", { cls:"outline", disabled }) : ""}
    ${pending && !admin && stage === "correction" ? button("Edit this request", "edit-correction", { disabled }) : ""}
    ${pending && !admin && stage === "quoted" ? `<div class="review-buttons">${button("Approve price","approve-quote",{ disabled })}${button("Decline quote","decline-quote",{cls:"outline", disabled})}</div>` : ""}
  </section>`;
}

function detailView(s) {
 const o=s.orders.find(item=>item.id===s.selected);
 if(!o)return '<section class="card empty"><h2>Order unavailable</h2><p>Refresh Orders to load the latest requests.</p><a class="button" href="#orders">Back to orders</a></section>';
 const admin=s.session.admin,closed=terminal.includes(o.status),options=o.options;
 const disabled=s.busy||s.cached||(!s.online&&s.mode==='live');
 const steps=['Pending','Accepted','Printing','Ready for Pickup','Completed'],position=steps.indexOf(o.status);
 const canEstimate=['Pending','Accepted','Printing'].includes(o.status);
 const transitions={Pending:canAccept(o)?['Accepted','Rejected']:['Rejected'],Accepted:['Printing'],Printing:['Ready for Pickup'],'Ready for Pickup':['Completed']}[o.status]||[];
 const checklistPending = s.printToolsAvailable && o.status === 'Printing' &&
   (o.printedFiles?.length ?? 0) !== 1 + (o.attachments?.length ?? 0);
 return `<div class="page-title"><div><button class="text-action" data-action="back">${icon('back')} Back to orders</button><h1>${admin?'Request workspace':'Your print request'}</h1><p class="muted">${e(o.fileName)}</p></div>${badge(reviewLabel(o))}</div>
 <div class="detail-grid"><div class="stack">
  ${orderReviewPanel(o, admin, s.reviewAvailable, disabled)}
  ${admin && !o.documentDeletedAt ? printChecklist(o, s, disabled) : ""}
  <section class="card pickup-overview"><div class="eyebrow">${o.status==='Ready for Pickup'?'Your prints are ready':'Pickup progress'}</div>
   <div class="queue-large">${o.queue?`Queue #${String(o.queue).padStart(3,'0')}`:closed?e(o.status):'Request received'}</div>
   <p class="small">${o.queueDay?e(o.queueDay)+' · '+e((s.settings||defaultShop()).name):'Submitted '+dateLabel(o.createdAt)}</p>
   ${!['Rejected','Cancelled','Completed'].includes(o.status)?`<div class="pickup-estimate"><span class="estimate-symbol">${icon('clock')}</span><div><b>${e(pickupLabel(o))}</b><p class="small">${o.status==='Ready for Pickup'?'Collect and pay at the shop.':o.expectedReadyAt?'This is the shop’s estimate and may change.':'The shop will confirm after reviewing your document.'}</p></div></div>`:''}
   ${!admin&&!['Rejected','Cancelled','Completed'].includes(o.status)?`<div class="collection-pass"><div><b>Your collection code</b><p class="small">Show this when you collect your prints.</p></div><strong class="collection-code">${e(collectionCode(o.id))}</strong></div>`:''}
   ${admin&&canEstimate?button(o.expectedReadyAt?'Update pickup estimate':'Set pickup estimate','estimate',{cls:'outline',icon:'clock',disabled}):''}
   ${o.note?`<div class="notice ${o.status==='Rejected'?'error':''}">${icon('info')}<span><b>Shop note:</b> ${e(o.note)}</span></div>`:''}
  </section>
  ${position>=0?`<section class="card"><h3>From upload to pickup</h3><div class="timeline">${steps.map((step,i)=>`<div class="timeline-item ${i<=position?'done':''}"><span class="round">${i<=position?icon('check'):''}</span><span>${step}</span>${i===position?'<span class="badge">Current</span>':''}</div>`).join('')}</div></section>`:''}
  ${shopCard(s)}
 </div><aside class="stack"><section class="card stack"><div class="row">${icon('pdf','big')}<div class="grow"><h3 class="detail-file">${e(o.fileName)}</h3><p class="small">${e(o.studentName)}</p></div></div><hr>
  ${[['Paper',paperLabel(options.paper)],['Selected pages',pageLabel(options)],['Copies',options.copies],['Print mode',options.color?'Color':'Black & white'],['Sides',options.duplex?'Double-sided':'Single-sided'],['Page sizing',printScaleLabel(options)],['Sheets',estimate(options).sheets],['Estimated total',(requiresPaperQuote(options.paper) || o.attachments?.length) ? 'Shop quote' : pesos(orderTotal(o))],['Last update',dateLabel(o.updatedAt||o.createdAt)]].map(([key,value])=>`<div class="price-line"><span>${key}</span><b>${e(value)}</b></div>`).join('')}
  ${o.documentDeletedAt?'<div class="notice">'+icon('info')+'<span>The PDF is no longer available. Your order history stays saved.</span></div>':button('View PDF','order-preview',{cls:'outline',icon:'eye',disabled:s.busy})}
  ${admin && s.mode === "live" && s.reviewAvailable && o.status === "Completed" &&
    !o.documentDeletedAt && (s.settings?.retentionDays || 0) > 0 && o.completedAt &&
    Date.now() >= o.completedAt + s.settings.retentionDays * 86400000 &&
    o.storagePath === `hosted/${o.ownerId}/${o.id}/${o.fileHash}.pdf`
      ? button("Remove expired PDF", "cleanup-pdf", { cls:"outline", disabled }) : ""}
  ${!admin&&closed&&!o.documentDeletedAt?button('Print this again','reorder',{icon:'refresh',disabled:s.busy}):''}
  ${!admin && o.attachments?.length ? `<div class="extra-files stack"><h3>Additional PDFs</h3>
    ${o.attachments.map((item,index) => `<div class="extra-file stack"><b class="text-wrap">${e(item.fileName)}</b>
      <p class="small">${e(paperLabel(item.options.paper))} · ${item.options.pages} pages · ${item.options.copies} copies · ${item.options.color ? "Color" : "Black & white"} · ${item.options.duplex ? "Double-sided" : "Single-sided"} · ${printScaleLabel(item.options)}</p>
      ${!o.documentDeletedAt ? `<button class="button outline" data-action="attachment-preview" data-index="${index+1}" ${s.busy ? "disabled" : ""}>View PDF</button>` : ""}
    </div>`).join("")}</div>` : ""}
  <small class="text-wrap">Request ID: ${e(o.id)}</small>
 </section>
 ${admin&&!closed?`<section class="card action-panel"><h3>Next step</h3><p class="small">${o.status==='Pending'?(canAccept(o) ? 'Review the PDF and print options before accepting.' : 'Finish the shop review and wait for approval before accepting.'):o.status==='Ready for Pickup'?'Check the student’s collection code before handover.':checklistPending?'Mark every file printed in the checklist.':'Update the status as the job progresses.'}</p>${o.status==='Ready for Pickup'&&s.mode!=='demo'?`<button class="button outline" data-action="push-notify" data-id="${e(o.id)}" ${disabled?'disabled':''}>Send pickup alert</button>`:''}${transitions.map(next=>`<button class="button ${next==='Rejected'?'outline':''}" data-action="status" data-status="${next}" ${disabled || (next === 'Ready for Pickup' && checklistPending) ? 'disabled' : ''}>${next==='Accepted'?'Accept & assign queue':next==='Rejected'?'Reject request':next==='Completed'?'Verify collection':'Mark as '+next}</button>`).join('')}</section>`:!admin&&o.status==='Pending'?`<button class="button outline" data-action="status" data-status="Cancelled" ${disabled?'disabled':''}>Cancel request</button>`:''}
 <p class="small">Pay at pickup. An estimate helps you plan; the shop confirms the final price.</p>
 </aside></div>`;
}

function printChecklist(order, s, disabled) {
  const files = [order, ...(order.attachments ?? [])];
  const canCheck = s.printToolsAvailable && order.status === "Printing";
  return `<section class="card stack print-checklist"><div class="row between"><h2>${canCheck ? "Print checklist" : "Files to print"}</h2>
    ${canCheck ? `<span class="badge">${order.printedFiles?.length ?? 0} of ${files.length} printed</span>` : ""}</div>
    ${files.map((file, index) => {
      const options = file.options, checked = order.printedFiles?.includes(index);
      return `<article class="print-file-row">
        <div class="row">${icon("pdf")}<h3 class="text-wrap">${e(file.fileName)}</h3></div>
        <p class="small">${e(paperLabel(options.paper))} · ${e(pageLabel(options))} · ${options.copies} ${options.copies === 1 ? "copy" : "copies"}</p>
        <p class="small">${options.color ? "Color" : "Black & white"} · ${options.duplex ? "Double-sided" : "Single-sided"} · ${printScaleLabel(options)}</p>
        <div class="row print-file-actions">
          <button class="button outline" data-action="${index ? "attachment-preview" : "order-preview"}" data-index="${index}" ${s.busy ? "disabled" : ""}>${icon("eye")} Preview</button>
          <button class="button outline" data-action="download-print-file" data-index="${index}" ${s.busy ? "disabled" : ""}>${icon("download")} Download PDF</button>
          ${canCheck ? `<button class="button ${checked ? "outline" : ""}" data-action="mark-printed" data-index="${index}" aria-pressed="${!!checked}" ${disabled ? "disabled" : ""}>${checked ? "Printed · undo" : "Mark printed"}</button>` : ""}
        </div></article>`;
    }).join("")}</section>`;
}

function accountView(s) {
  return `<div class="page-title"><div><div class="eyebrow">Your workspace</div><h1>Account & print guide.</h1><p class="muted">A few useful details for your next print.</p></div></div><div class="account-grid"><section class="card stack">${avatar(s.session, true)}<div><h2>${e(s.session.name)}</h2><p class="muted text-wrap" style="margin-top:8px">${e(s.session.email)}</p></div><span class="badge" style="align-self:start">${s.session.admin ? "Shop administrator" : "Student"}</span>${button("Sign out", "sign-out", { cls: "outline", icon: "logout", disabled: s.busy })}</section>${!s.session.admin&&s.mode!=="demo"?`<section class="card stack"><div class="eyebrow">Pickup updates</div><h2>A heads-up when it’s ready.</h2><p class="small">${s.push?.enabled?"Alerts are enabled on this device.":"Enable background pickup alerts on this device. On iPhone, first add PrintEase to your Home Screen in Safari, then open the installed app."}</p>${button(s.push?.enabled?"Turn off alerts":"Enable pickup alerts",s.push?.enabled?"push-disable":"push-enable",{cls:"outline",disabled:s.busy||s.push?.syncing})}${s.push?.enabled?button("Send a test alert","push-test",{cls:"ghost",disabled:s.busy}):""}${s.push?.message?`<p class="notice" role="status">${e(s.push.message)}</p>`:""}<small>Delivery can be delayed by your device or connection. Orders always shows the latest synced status.</small></section>`:""}${shopCard(s, true)}<section class="card stack"><div class="eyebrow">Know your total</div><h2>Simple, clear pricing.</h2><p class="small">${s.settings?.ratesConfirmed?"Shop-confirmed rates. Existing orders keep their estimate.":"Sample estimates. Confirm the actual printing price with the shop."}</p>${[["A4 / Letter \xB7 B&W", pesos((s.settings || defaultShop()).rates.bw) + " / side"], ["A4 / Letter \xB7 Color", pesos((s.settings || defaultShop()).rates.color) + " / side"], ["Legal paper", "+" + pesos((s.settings || defaultShop()).rates.legalExtra) + " / side"]].map(([a, b]) => `<div class="price-line"><span>${a}</span><b>${b}</b></div>`).join("")}<hr><p class="small">Selected pages \xD7 copies \xD7 rate per printed side. Duplex saves sheets. All selected pages use the same color mode. PDF, JPG, and PNG up to 5 MB per file. Export Word, PowerPoint, and Excel to PDF first.</p>${s.session.admin ? button("Manage shop details & rates", "shop-settings", { cls: "outline", icon: "store", disabled: s.busy }) : ""}</section><section class="card stack"><div class="row">${icon("phone", "big")}<h2>A place on your Home Screen.</h2></div><p class="small">Use PrintEase like an app on your iPhone. No App Store download needed.</p><ol class="install-steps"><li>Open this website in <b>Safari</b>.</li><li>Tap the <b>Share</b> button ${icon("share")}.</li><li>Choose <b>Add to Home Screen</b>, then tap <b>Add</b>.</li></ol><small>Live submissions need internet. Enable pickup alerts in Account, or keep PrintEase open for updates. The local demo and saved draft remain available offline after your first visit.</small></section><section class="card stack">${s.mode === "demo" ? `<h2>Try the other side.</h2><p class="small">Switch roles to review the same requests. Demo orders are local to this browser, not shared with Android or other devices.</p>${button(s.session.admin ? "Switch to student" : "Switch to administrator", "switch-role", { cls: "outline", disabled: s.busy })}` : `<h2>One account. One queue.</h2><p class="small">The website and Android app use the same PrintEase Firebase accounts and orders. Live roles are assigned by the shop\u2019s trusted administrator.</p>${button("Refresh account & orders", "refresh", { cls: "outline", icon: "refresh", disabled: s.busy })}`}</section></div><p class="footnote">${icon("info")}<span>PrintEase Web \xB7 BSU Alangilan Campus \xB7 Actual printing and payment happen at the shop.</span></p>`;
}
const nanoPrintServices = [
  "Print & edit",
  "Photocopy",
  "Bookbind",
  "Scan",
  "Tarpapel",
  "School supplies",
  "Laminate",
  "Rush ID",
  "Vellum / Magazine",
  "Tarpaulin layout & CAD",
  "Brochure & Booklet",
];

function shopServices() {
  return `<details class="shop-services">
    <summary id="shop-services-toggle">View shop services</summary>
    <p>Send PDF print requests in PrintEase. For other services and prices, contact the shop.</p>
    <ul>${nanoPrintServices.map(service => `<li>${e(service)}</li>`).join("")}</ul>
  </details>`;
}

function shopCard(s,account=false){
 const shop=s.settings||defaultShop();
 const generic=shop.name==='Nearby print shop'&&shop.address==='Near BSU Alangilan campus';
 const query=generic?'printing shops near BSU Alangilan':`${shop.name} ${shop.address}`;
 const isNanoPrint = ['Nano Print Alangilan','Nano Alangilan'].includes(shop.name);
 const mapsUrl=isNanoPrint?'https://maps.app.goo.gl/gBCZetYJyz46RoeQ9':`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
 return `<section class="card shop-card ${account?'':'shop-overview'}"><div class="shop-symbol">${icon('store')}</div><div class="shop-info"><div class="row between"><h3>${e(shop.name)}</h3><span class="availability ${shop.acceptingOrders?'':'paused'}">${shop.acceptingOrders?'Accepting requests':'Requests paused'}</span></div><p>${e(shop.address)}</p><div class="shop-hours">${icon('clock')}<span>${e(shop.pickupHours)}</span></div><small>Collect and pay at the shop near campus.</small>${isNanoPrint ? shopServices() : ""}</div><div class="shop-actions"><a class="button outline" href="${e(mapsUrl)}" target="_blank" rel="noopener noreferrer">${icon('store')}${generic?'Find shops near campus':'View location'}</a>${shop.contactEmail?`<a class="button ghost" href="mailto:${encodeURIComponent(shop.contactEmail)}">Email shop</a>`:''}${shop.facebookUrl?`<a class="button ghost" href="${e(shop.facebookUrl)}" target="_blank" rel="noopener noreferrer">Facebook</a>`:''}</div></section>`;
}

function pickupScheduleSettings(shop, busy) {
  const schedule = shop.pickupSchedule;
  const disabled = busy ? "disabled" : "";
  return `<section class="card stack"><h2>Pickup schedule</h2>
    <label class="switch-row"><span>Show pickup windows</span><input id="calendar-enabled" data-calendar="enabled" type="checkbox" ${schedule ? "checked" : ""} ${disabled}></label>
    ${schedule ? `<fieldset class="schedule-days"><legend>Opening days</legend>
      ${weekdays.map((day,index) => `<label><input type="checkbox" id="schedule-day-${index+1}" data-calendar="day" value="${index+1}"
        ${schedule.days.includes(index+1) ? "checked" : ""} ${disabled}>${day}</label>`).join("")}
    </fieldset><div class="paper-dimensions">
      ${[["open","Opens"],["close","Closes"],["cutoff","Same-day cutoff"]].map(([key,label]) => `<label>${label}<input id="schedule-${key}" data-calendar="${key}" type="time" value="${inputTime(schedule[key])}" ${key === "cutoff" ? "" : "required"} ${disabled}></label>`).join("")}
    </div>
    <label>Closure dates<input id="schedule-closed" data-calendar="closed" value="${e(schedule.closed)}" placeholder="YYYY-MM-DD, YYYY-MM-DD" maxlength="153" ${disabled}><small>Separate dates with commas. Times use Philippine time.</small></label>` : ""}
  </section>`;
}

function paperStockSettings(shop, busy) {
  return `<section class="card stack"><h2>Paper availability</h2>
    <p class="small">Students see these statuses in the paper menu. Out-of-stock sizes cannot be selected for new requests.</p>
    <div class="stock-settings">${stockPapers.map((paper, index) => `<label>${paper === "Custom" ? "Custom sizes" : paper}
      <select id="stock-${index}" data-stock="${paper}" ${busy ? "disabled" : ""}>
        ${Object.entries(stockLabels).map(([key,label]) => `<option value="${key}" ${paperStock(shop, paper) === key ? "selected" : ""}>${label}</option>`).join("")}
      </select></label>`).join("")}</div></section>`;
}

function settingsView(s) {
  const shop = s.shopEdit || s.settings || defaultShop();
  const field = (id, label, value, max) => `<label>${label}<input id="shop-${id}" data-setting="${id}" value="${e(value)}" maxlength="${max}" required ${s.busy ? "disabled" : ""}></label>`;
  return `<div class="page-title"><div><button class="text-action" data-action="account-back">${icon("back")} Back to account</button><div class="eyebrow">Your shop, your way</div><h1>Shop settings.</h1><p class="muted">Give students a clear place, time, and price for pickup.</p></div></div><form id="shop-form" class="settings-layout"><section class="card stack"><h2>Pickup details</h2>${field("name", "Shop name", shop.name, 100)}${field("address", "Address or landmark near campus", shop.address, 300)}${field("pickupHours", "Pickup hours", shop.pickupHours, 200)}<label>Email contact<input id="shop-contactEmail" data-setting="contactEmail" type="email" maxlength="254" value="${e(shop.contactEmail||'')}" ${s.busy?'disabled':''}></label><label>Facebook page<input id="shop-facebookUrl" data-setting="facebookUrl" type="url" maxlength="2048" placeholder="https://www.facebook.com/…" value="${e(shop.facebookUrl||'')}" ${s.busy?'disabled':''}></label><label class="switch-row"><span>Accept new requests<small>Pause submissions when your shop is unavailable.</small></span><input id="shop-acceptingOrders" data-setting="acceptingOrders" type="checkbox" ${shop.acceptingOrders ? "checked" : ""} ${s.busy ? "disabled" : ""}></label></section><section class="card stack"><h2>Print rates</h2><label class="switch-row"><span>Rates confirmed with the shop<small>Leave off until the shop verifies the prices below.</small></span><input id="shop-ratesConfirmed" data-setting="ratesConfirmed" type="checkbox" ${shop.ratesConfirmed?'checked':''} ${s.busy?'disabled':''}></label><p class="small">Whole Philippine pesos per printed side. New rates apply to new requests; submitted quotes stay saved.</p>${[["bw", "Black & white \xB7 A4 / Letter", 1], ["color", "Color \xB7 A4 / Letter", 1], ["legalExtra", "Legal paper surcharge", 0]].map(([key, label, min]) => `<label>${label}<div class="currency-input"><span>\u20B1</span><input id="rate-${key}" data-rate="${key}" type="number" inputmode="numeric" min="${min}" max="1000" step="1" value="${e(shop.rates[key])}" required ${s.busy ? "disabled" : ""}></div></label>`).join("")}</section>${s.printToolsAvailable ? paperStockSettings(shop, s.busy) : ""}${s.paperSizesAvailable ? pickupScheduleSettings(shop, s.busy) : ""}<section class="card stack"><h2>PDF retention</h2>${s.reviewAvailable && s.mode === "live" ? `<p class="small">Set how long to keep completed order PDFs. Staff can remove eligible files from the order screen. Order history stays saved.</p><label>Keep files after pickup<select data-setting="retentionDays" ${s.busy ? "disabled" : ""}>${[0,7,14,30].map(days => `<option value="${days}" ${shop.retentionDays === days ? "selected" : ""}>${days ? days + " days" : "Keep files"}</option>`).join("")}</select></label>` : '<p class="small">PDF cleanup is unavailable in this workspace.</p>'}</section><div class="settings-save"><button type="submit" class="button" ${s.busy ? "disabled" : ""}>${icon("check")}${s.busy ? "Saving\u2026" : "Save shop details"}</button><p class="small">Students see these details on Android and the web.</p></div></form>`;
}
export {
  accountView,
  authView,
  avatar,
  badge,
  brand,
  dashboard,
  detailView,
  icon,
  orderCard,
  ordersList,
  requestView,
  settingsView,
  shell,
  shopCard
};
