import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
// Only /api is proxied. No .env from the backend is loaded or exposed to the client.
export default defineConfig({
    plugins: [vue()],
    server: {
        port: 5173,
        strictPort: true,
        proxy: {
            "/api": {
                target: process.env.BACKEND_URL || "http://127.0.0.1:8080",
                changeOrigin: true,
            },
        },
    },
    preview: {
        port: 4173,
        strictPort: true,
        proxy: {
            "/api": {
                target: process.env.BACKEND_URL || "http://127.0.0.1:8080",
                changeOrigin: true,
            },
        },
    },
});
