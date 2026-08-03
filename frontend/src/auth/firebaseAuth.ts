import type { Auth, User } from "firebase/auth";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

const authEmulatorUrl = import.meta.env.VITE_FIREBASE_AUTH_EMULATOR_URL;
const userInitializations = new WeakMap<Auth, Promise<User>>();

function validatedEmulatorUrl(value: string) {
  const url = new URL(value);
  if (
    url.protocol !== "http:" ||
    (url.hostname !== "127.0.0.1" && url.hostname !== "localhost")
  ) {
    throw new Error("Firebase Auth Emulator must use a loopback HTTP URL");
  }
  return url.origin;
}

export function hasFirebaseConfig() {
  return Object.values(firebaseConfig).every(Boolean);
}

export async function getFirebaseAuth(): Promise<Auth> {
  const [{ getApp, getApps, initializeApp }, { connectAuthEmulator, getAuth }] =
    await Promise.all([import("firebase/app"), import("firebase/auth")]);
  const app = getApps().length ? getApp() : initializeApp(firebaseConfig);
  const auth = getAuth(app);
  if (authEmulatorUrl && !auth.emulatorConfig) {
    if (!firebaseConfig.projectId?.startsWith("demo-")) {
      throw new Error("Firebase Auth Emulator requires a demo- project ID");
    }
    connectAuthEmulator(auth, validatedEmulatorUrl(authEmulatorUrl), {
      disableWarnings: true,
    });
  }
  return auth;
}

export async function ensureFirebaseUser(auth: Auth): Promise<User> {
  const existingInitialization = userInitializations.get(auth);
  if (existingInitialization) {
    return existingInitialization;
  }

  const initialization = initializeFirebaseUser(auth);
  userInitializations.set(auth, initialization);
  try {
    return await initialization;
  } finally {
    if (userInitializations.get(auth) === initialization) {
      userInitializations.delete(auth);
    }
  }
}

async function initializeFirebaseUser(auth: Auth): Promise<User> {
  const {
    browserLocalPersistence,
    onAuthStateChanged,
    setPersistence,
    signInAnonymously,
  } = await import("firebase/auth");
  await setPersistence(auth, browserLocalPersistence);

  const currentUser = await new Promise<User | null>((resolve) => {
    const unsubscribe = onAuthStateChanged(auth, (user) => {
      unsubscribe();
      resolve(user);
    });
  });

  if (currentUser) {
    return currentUser;
  }

  return (await signInAnonymously(auth)).user;
}
