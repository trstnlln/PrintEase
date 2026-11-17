export const retentionChoices = [0, 7, 14, 30];
export function readyTransition(before, after) {
  return before?.status !== 'Ready for Pickup' && after?.status === 'Ready for Pickup' && typeof after.ownerId === 'string';
}
export function expiredDocument(order, days, now = Date.now()) {
  return retentionChoices.includes(days) && days > 0 && order.status === 'Completed' &&
    Number.isSafeInteger(order.completedAt) && order.completedAt > 0 && order.completedAt <= now - days * 86400000 &&
    !order.documentDeletedAt && /^documents\/[^/]+\/[^/]+\.pdf$/.test(order.storagePath || '') &&
    order.storagePath.split('/')[1] === order.ownerId;
}
