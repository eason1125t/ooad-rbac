/** 功能：OA独立构建入口；后续公文功能保留在本应用，当前共享登录和页面骨架。 */
import { mount } from "@municipal/shell";
import "@municipal/shell/styles.css";
mount("oa", import.meta.env.BASE_URL);
