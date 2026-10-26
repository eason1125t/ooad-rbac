/** 功能：admin路径/构建及本地同站点代理；OA/PMIS通过此站点访问其他Vite进程，Cookie无需跨域。 */
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig({
  base: "/admin/",
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: {
      "/api": process.env.API_DEV_URL ?? "http://127.0.0.1:8080",
      "/oa/": process.env.OA_DEV_URL ?? "http://127.0.0.1:5174",
      "/pmis/": process.env.PMIS_DEV_URL ?? "http://127.0.0.1:5175",
    },
  },
});
