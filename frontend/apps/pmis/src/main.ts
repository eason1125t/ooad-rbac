/** 功能：PMIS真实立项入口；共享登录守卫，项目预算和审批由PmisWorkspace维护。 */
import { mount } from "@municipal/shell";
import "@municipal/shell/styles.css";
import PmisWorkspace from "./PmisWorkspace.vue";
mount("pmis", import.meta.env.BASE_URL, PmisWorkspace);
