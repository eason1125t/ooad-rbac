/** 功能：Pinia登录状态适配；服务端me是身份来源，刷新/401时重新验证，不持久化密码或权限。 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { api, ApiError, type Me } from "@municipal/api";

export const useSession = defineStore("session", () => {
  const user = ref<Me>();
  const checked = ref(false);
  const error = ref("");
  async function refresh() {
    try {
      user.value = await api.me();
      error.value = "";
    } catch (failure) {
      user.value = undefined;
      if (!(failure instanceof ApiError && failure.status === 401))
        error.value =
          failure instanceof Error ? failure.message : "身份服务不可用";
    } finally {
      checked.value = true;
    }
  }
  return { user, checked, error, refresh };
});
