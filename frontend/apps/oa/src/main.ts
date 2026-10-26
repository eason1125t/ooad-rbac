/** 功能：OA真实公文入口；共享登录守卫与壳，业务字段和流程由OaWorkspace实现。 */
import { mount } from "@municipal/shell";
import "@municipal/shell/styles.css";
import OaWorkspace from "./OaWorkspace.vue";
mount("oa", import.meta.env.BASE_URL, OaWorkspace);
