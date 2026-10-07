import { defineConfig } from "@playwright/test";
export default defineConfig({
    testDir: "./tests/e2e",
    timeout: 60000,
    fullyParallel: false,
    workers: 1,
    retries: 0,
    reporter: "list",
    use: {
        baseURL: process.env.UI_URL || "http://127.0.0.1:5173",
        browserName: "chromium",
        headless: true,
        trace: "off",
        screenshot: "off",
    },
    projects: [
        { name: "desktop", use: { viewport: { width: 1440, height: 1000 } } },
    ],
});
