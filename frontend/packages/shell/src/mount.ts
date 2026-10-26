/** 功能：挂载三个应用共用的小型工作台；应用ID/base由各自main提供，共享路由/登录和状态展示。 */
import { createApp, type Component } from "vue";
import { createPinia } from "pinia";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import Login from "./Login.vue";
import Workspace from "./Workspace.vue";
import { useSession } from "./session";

export function mount(
  application: "admin" | "oa" | "pmis",
  base: string,
  workspace?: Component,
) {
  const pinia = createPinia();
  const router = createRouter({
    history: createWebHistory(base),
    routes: [
      {
        path: "/",
        redirect: "/workspace",
      },
      { path: "/login", component: Login },
      {
        path:
          application === "admin"
            ? "/:page(workspace|organizations|roles|applications|audit)"
            : "/:page(workspace)",
        component: workspace ?? Workspace,
        props: true,
      },
      ...(application === "admin"
        ? [
            {
              path: "/validity",
              redirect: { path: "/roles", query: { tab: "members" } },
            },
            {
              path: "/imports",
              redirect: { path: "/organizations", query: { tab: "imports" } },
            },
          ]
        : []),
      { path: "/:pathMatch(.*)*", redirect: "/" },
    ],
  });
  if (workspace)
    router.beforeEach(async (to) => {
      const session = useSession(pinia);
      if (!session.checked) await session.refresh();
      if (to.path !== "/login" && !session.user)
        return { path: "/login", query: { redirect: to.fullPath } };
    });
  window.addEventListener("municipal:session-expired", () => {
    useSession(pinia).user = undefined;
    void router.push("/login");
  });
  createApp(App)
    .provide("application", application)
    .use(pinia)
    .use(router)
    .mount("#app");
}
