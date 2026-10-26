/** 功能：OA固定子路径和API代理，与管理站点共用同站点Cookie。 */
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig({
  base: "/oa/",
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: { "/api": process.env.API_DEV_URL ?? "http://127.0.0.1:8080" },
  },
});
