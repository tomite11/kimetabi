import type { Auth, User } from "firebase/auth";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

export function hasFirebaseConfig() {
  return Object.values(firebaseConfig).every(Boolean);
}

export async function getFirebaseAuth(): Promise<Auth> {
  const [{ getApp, getApps, initializeApp }, { getAuth }] = await Promise.all([
    import("firebase/app"),
    import("firebase/auth"),
  ]);
  const app = getApps().length ? getApp() : initializeApp(firebaseConfig);
  return getAuth(app);
}

export async function ensureFirebaseUser(auth: Auth): Promise<User> {
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
