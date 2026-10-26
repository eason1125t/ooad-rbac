<!-- 功能：三端可复用的首次改密入口；成功后清除当前主体并重新登录，不绕过服务器强改密限制。 -->
<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api } from "@municipal/api";
import { useSession } from "./session";
const session = useSession(),
  router = useRouter();
const currentPassword = ref(""),
  newPassword = ref(""),
  error = ref(""),
  busy = ref(false);
async function save() {
  busy.value = true;
  error.value = "";
  try {
    await api.changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    });
    currentPassword.value = "";
    newPassword.value = "";
    session.user = undefined;
    await router.push("/login");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "修改失败，请重新检查";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel">
    <h1>首次登录请修改密码</h1>
    <p>新密码至少12个字符，修改后重新登录。</p>
    <p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <form class="record-form" :inert="busy" @submit.prevent="save">
      <label
        >当前密码<input
          v-model="currentPassword"
          type="password"
          required
          autocomplete="current-password" /></label
      ><label
        >新密码<input
          v-model="newPassword"
          type="password"
          required
          minlength="12"
          maxlength="72"
          autocomplete="new-password" /></label
      ><button class="primary" :disabled="busy">修改密码并重新登录</button>
    </form>
  </section>
</template>
