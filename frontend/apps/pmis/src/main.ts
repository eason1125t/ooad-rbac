/** 功能：PMIS独立构建入口；共享登录/请求处理，不提前实现工程审批。 */
import { mount } from "@municipal/shell";
import "@municipal/shell/styles.css";
mount("pmis", import.meta.env.BASE_URL);
