import { registerSW } from "virtual:pwa-register";

export type PwaState = {
  offline: boolean;
  updateAvailable: boolean;
};

let state: PwaState = {
  offline: typeof navigator !== "undefined" ? !navigator.onLine : false,
  updateAvailable: false,
};
let applyUpdate: ((reloadPage?: boolean) => Promise<void>) | undefined;
const listeners = new Set<() => void>();

function publish(next: Partial<PwaState>) {
  state = { ...state, ...next };
  listeners.forEach((listener) => listener());
}

export function getPwaState() {
  return state;
}

export function subscribeToPwaState(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function initializePwa() {
  window.addEventListener("online", () => publish({ offline: false }));
  window.addEventListener("offline", () => publish({ offline: true }));

  applyUpdate = registerSW({
    immediate: true,
    onNeedRefresh: () => publish({ updateAvailable: true }),
  });
}

export async function activatePwaUpdate() {
  // Dexie operations are intentionally retained across the reload. They are
  // removed only after the API confirms success in the queue implementation.
  await applyUpdate?.(true);
}
