/** 功能：admin独立构建入口；路由、CSRF客户端与小型UI由共享包提供。 */
import { mount } from "@municipal/shell";
import "@municipal/shell/styles.css";
import AdminWorkspace from "./AdminWorkspace.vue";
import "./admin.css";
mount("admin", import.meta.env.BASE_URL, AdminWorkspace);
