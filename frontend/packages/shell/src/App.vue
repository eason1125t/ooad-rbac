<!-- 功能：三应用公共导航与登录状态；与session.ts及路由连接，不将菜单隐藏作为服务端权限检查。 -->
<script setup lang="ts">
import { inject, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api } from "@municipal/api";
import { useSession } from "./session";
const application = inject<string>("application")!;
const session = useSession();
const router = useRouter();
const message = ref("");
const titles: Record<string, string> = {
  admin: "统一权限管理",
  oa: "OA 公文管理",
  pmis: "工程项目管理",
};
onMounted(() => session.refresh());
async function logout() {
  try {
    await api.logout();
    session.user = undefined;
    await router.push("/login");
  } catch (failure) {
    message.value =
      failure instanceof Error ? failure.message : "退出失败，请重试";
  }
}
</script>
<template>
  <div class="app-shell">
    <aside class="site-nav">
      <a class="brand" :href="`/${application}/`"
        ><span class="brand-mark" aria-hidden="true">市</span
        ><span
          >市政协同<br /><small>{{ titles[application] }}</small></span
        ></a
      >
      <nav aria-label="主导航">
        <template v-if="application === 'admin'">
          <RouterLink to="/workspace">工作台</RouterLink>
          <RouterLink to="/organizations">组织与用户</RouterLink>
          <RouterLink to="/roles">角色与授权</RouterLink>
          <RouterLink to="/applications">应用与资源</RouterLink>
          <RouterLink to="/audit">操作审计</RouterLink>
        </template>
        <RouterLink v-else to="/workspace">{{
          application === "oa" ? "我的公文" : "我的工程项目"
        }}</RouterLink>
      </nav>
      <div class="site-links">
        <a href="/admin/">权限管理</a><a href="/oa/">OA 公文</a
        ><a href="/pmis/">工程项目</a>
      </div>
      <p class="nav-note">
        {{ application === "admin" ? "角色与授权管理" : "业务功能仍在建设"
        }}<br />组织、角色与业务权限分别管理
      </p>
    </aside>
    <div class="main-column">
      <header class="topbar">
        <span>市政企业 / {{ titles[application] }}</span>
        <div v-if="session.user" class="user-actions">
          <span>{{ session.user.username }}</span
          ><button @click="logout">退出登录</button>
        </div>
        <RouterLink v-else to="/login">登录账号</RouterLink>
      </header>
      <main>
        <p v-if="message || session.error" role="alert" class="error-box">
          {{ message || session.error }}
        </p>
        <RouterView />
      </main>
    </div>
  </div>
</template>
