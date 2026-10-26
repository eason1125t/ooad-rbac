<!-- 功能：真实登录表单；通过共享客户端取得CSRF并发起Cookie认证，成功后刷新服务端身份。 -->
<script setup lang="ts">
import { ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { api, ApiError } from "@municipal/api";
import { useSession } from "./session";
const username = ref("");
const password = ref("");
const busy = ref(false);
const error = ref("");
const router = useRouter();
const route = useRoute();
const session = useSession();
async function login() {
  busy.value = true;
  error.value = "";
  try {
    session.user = await api.login(username.value, password.value);
    password.value = "";
    const target = route.query.redirect;
    await router.push(
      typeof target === "string" &&
        target.startsWith("/") &&
        !target.startsWith("//")
        ? target
        : "/",
    );
  } catch (failure) {
    error.value =
      failure instanceof ApiError
        ? `${failure.message}${failure.traceId ? `（请求编号 ${failure.traceId}）` : ""}`
        : "连接失败，请检查服务状态";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-panel">
    <h1>登录工作台</h1>
    <p class="muted">使用管理员配置的账号。首次账号通过服务器环境初始化。</p>
    <form @submit.prevent="login">
      <label for="username">账号</label
      ><input
        id="username"
        v-model="username"
        required
        maxlength="64"
        autocomplete="username"
      /><label for="password">密码</label
      ><input
        id="password"
        v-model="password"
        type="password"
        required
        autocomplete="current-password"
      />
      <p v-if="error" class="error-box" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">
        {{ busy ? "正在登录…" : "登录" }}
      </button>
    </form>
  </div>
</template>
