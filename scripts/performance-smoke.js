/** 功能：后续小负载k6入口；用户提供现有合法会话/目标权限，不将smoke解释为万级性能验收。 */
import http from 'k6/http';
import { check } from 'k6';
export const options = { vus: 2, duration: '10s' };
export default function () {
  if (!__ENV.SESSION_COOKIE) throw new Error('SESSION_COOKIE required; never write it to logs');
  const response = http.get(`${__ENV.BASE_URL || 'http://127.0.0.1:8080'}/api/v1/oa/demo`, { headers: { Cookie: `SESSION=${__ENV.SESSION_COOKIE}` } });
  check(response, { 'authorized response 200': (result) => result.status === 200 });
}
