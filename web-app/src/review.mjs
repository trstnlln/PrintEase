export const addons = [
  { id: "bookbinding", label: "Bookbinding" },
  { id: "lamination", label: "Lamination" }
];

export const MAX_QUOTE = 200_000_000;

export function newReview(requested = []) {
  if (!Array.isArray(requested) ||
      requested.length > addons.length ||
      new Set(requested).size !== requested.length ||
      requested.some(id => !addons.some(addon => addon.id === id))) {
    throw new Error("Choose supported print add-ons.");
  }
  return { stage: "submitted", version: 0, addons: requested, note: "", quote: null };
}

export function canAccept(order) {
  return !order.review || order.review.stage === "approved";
}

export function reviewUpdate(order, session, action, version, input = {}) {
  if (!order || order.status !== "Pending" || (order.review?.version ?? -1) !== version) {
    throw new Error("This request changed. Refresh it before continuing.");
  }
  const current = order.review ?? newReview();
  const stage = current.stage;
  if (session?.admin) {
    if (["approved", "submitted", "revised", "quoted", "declined"].includes(stage) && action === "correction") {
      const note = input.note?.trim();
      if (!note || note.length > 500) throw new Error("Describe what the customer should correct.");
      return { ...current, stage: "correction", version: version + 1, note, quote: null };
    }
    if (["submitted", "revised", "quoted", "declined"].includes(stage) && action === "quote") {
      const note = input.note?.trim() || "";
      if (!Number.isSafeInteger(input.quote) || input.quote < 1 || input.quote > MAX_QUOTE || note.length > 500) {
        throw new Error("Enter a valid final price in whole pesos.");
      }
      return { ...current, stage: "quoted", version: version + 1, note, quote: input.quote };
    }
  } else if (session?.uid === order.ownerId) {
    if (stage === "quoted" && action === "approve") {
      return { ...current, stage: "approved", version: version + 1 };
    }
    if (stage === "correction" && action === "revise") {
      return { ...current, stage: "revised", version: version + 1, note: "", quote: null };
    }
    if (stage === "quoted" && action === "decline") {
      return { ...current, stage: "declined", version: version + 1, quote: null, note: "" };
    }
  }
  throw new Error("This action is unavailable for the current request.");
}

export function reviewLabel(order) {
  if (order.status !== "Pending") return order.status;
  return {
    correction: "Correction requested",
    quoted: "Awaiting price approval",
    approved: "Price approved",
    revised: "Updated by student",
    declined: "Quote declined"
  }[order.review?.stage] ?? "Pending";
}

export const shopQueueGroups = ["Needs review", "Waiting for response", "Ready to print", "Printing", "Ready for pickup", "History"];

export function shopQueueGroup(order) {
  switch (order.status) {
    case "Pending":
      return ["quoted", "correction"].includes(order.review?.stage) ? "Waiting for response" : "Needs review";
    case "Accepted": return "Ready to print";
    case "Printing": return "Printing";
    case "Ready for Pickup": return "Ready for pickup";
    default: return "History";
  }
}
