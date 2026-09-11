import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { defineConfig } from "vitest/config";

function removeForwardedBrowserOrigin(proxy: {
  on: (
    event: "proxyReq",
    listener: (request: { removeHeader: (name: string) => void }) => void,
  ) => void;
}) {
  proxy.on("proxyReq", (request) => request.removeHeader("origin"));
}

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "prompt",
      includeAssets: [
        "icons/app-icon-192.png",
        "icons/app-icon-512.png",
        "icons/app-icon-maskable-512.png",
        "icons/apple-touch-icon.png",
      ],
      manifest: {
        name: "タビキメワリ",
        short_name: "タビキメワリ",
        description: "旅行の候補決めから支出、精算までを一本につなぐ",
        lang: "ja",
        start_url: "/",
        scope: "/",
        display: "standalone",
        background_color: "#fbfcfa",
        theme_color: "#182b49",
        categories: ["travel", "finance", "productivity"],
        icons: [
          {
            src: "/icons/app-icon-192.png",
            sizes: "192x192",
            type: "image/png",
            purpose: "any",
          },
          {
            src: "/icons/app-icon-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "any",
          },
          {
            src: "/icons/app-icon-maskable-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable",
          },
        ],
      },
      workbox: {
        globIgnores: ["**/mockServiceWorker.js"],
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/api/"),
            handler: "NetworkOnly",
          },
        ],
      },
    }),
  ],
  test: {
    environment: "jsdom",
    environmentOptions: {
      jsdom: {
        url: "http://localhost:5173/",
      },
    },
    setupFiles: "./src/test/setup.ts",
    css: true,
    include: ["src/**/*.test.{ts,tsx}"],
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.VITE_DEV_API_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
        configure: removeForwardedBrowserOrigin,
      },
      "/ws": {
        target: process.env.VITE_DEV_API_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
