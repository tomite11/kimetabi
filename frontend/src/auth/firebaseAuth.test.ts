import { beforeEach, describe, expect, it, vi } from "vitest";

const signInAnonymously = vi.fn();
const setPersistence = vi.fn();
const onAuthStateChanged = vi.fn();

vi.mock("firebase/auth", () => ({
  browserLocalPersistence: { type: "LOCAL" },
  onAuthStateChanged,
  setPersistence,
  signInAnonymously,
}));

import { ensureFirebaseUser } from "./firebaseAuth";

describe("ensureFirebaseUser", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setPersistence.mockResolvedValue(undefined);
  });

  it("shares one anonymous sign-in across concurrent StrictMode effects", async () => {
    const auth = {};
    const user = { uid: "anonymous-user", isAnonymous: true };
    onAuthStateChanged.mockImplementation(
      (_auth, callback: (currentUser: null) => void) => {
        queueMicrotask(() => callback(null));
        return vi.fn();
      },
    );
    signInAnonymously.mockResolvedValue({ user });

    const [first, second] = await Promise.all([
      ensureFirebaseUser(auth as never),
      ensureFirebaseUser(auth as never),
    ]);

    expect(first).toBe(user);
    expect(second).toBe(user);
    expect(signInAnonymously).toHaveBeenCalledOnce();
  });
});
