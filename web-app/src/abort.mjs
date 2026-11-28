export function abortable(value, signal) {
  if (!signal) return Promise.resolve(value);
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    Promise.resolve(value).then(
      result => {
        signal.removeEventListener("abort", abort);
        resolve(result);
      },
      error => {
        signal.removeEventListener("abort", abort);
        reject(error);
      }
    );
    if (signal.aborted) abort();
    else signal.addEventListener("abort", abort, { once: true });
  });
}
