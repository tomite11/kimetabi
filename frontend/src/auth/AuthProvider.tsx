import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";

import { setAccessTokenProvider } from "../api/client";
import {
  ensureFirebaseUser,
  getFirebaseAuth,
  hasFirebaseConfig,
} from "./firebaseAuth";

type AuthState = {
  isAnonymous: boolean;
  status: "loading" | "ready" | "error";
};

const AuthContext = createContext<AuthState>({
  isAnonymous: true,
  status: "ready",
});

export function AuthProvider({ children }: PropsWithChildren) {
  const [state, setState] = useState<AuthState>({
    isAnonymous: true,
    status: "loading",
  });

  useEffect(() => {
    let active = true;

    if (!hasFirebaseConfig()) {
      if (import.meta.env.VITE_ENABLE_MSW === "true") {
        setAccessTokenProvider(async () => "msw-anonymous-token");
        setState({ isAnonymous: true, status: "ready" });
      } else {
        setState({ isAnonymous: true, status: "error" });
      }
      return () => setAccessTokenProvider();
    }

    void getFirebaseAuth()
      .then(ensureFirebaseUser)
      .then((user) => {
        if (!active) return;
        setAccessTokenProvider(() => user.getIdToken());
        setState({ isAnonymous: user.isAnonymous, status: "ready" });
      })
      .catch(() => {
        if (active) setState({ isAnonymous: true, status: "error" });
      });

    return () => {
      active = false;
      setAccessTokenProvider();
    };
  }, []);

  const value = useMemo(() => state, [state]);

  if (state.status === "loading") {
    return <p role="status">参加情報を準備しています…</p>;
  }

  if (state.status === "error") {
    return (
      <section aria-labelledby="auth-error-title">
        <h1 id="auth-error-title">参加情報を準備できませんでした</h1>
        <p>Firebaseの接続設定を確認して、ページを再読み込みしてください。</p>
      </section>
    );
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// The provider and its colocated hook intentionally share this small module.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  return useContext(AuthContext);
}
