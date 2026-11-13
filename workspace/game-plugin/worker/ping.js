/**
 * NBA Live Score 匿名统计端点（Cloudflare Worker + KV）
 *
 * 插件侧（opt-in，默认关闭）上报三类（均只含版本号/功能名/错误摘要，无机器信息）：
 * - 启动：GET /ping?v=版本            → KV 键 v:{版本}
 * - 功能首用：GET /ping?v=版本&func=x  → KV 键 f:{功能}:{版本}
 * - 业务错误：GET /ping?error=摘要      → KV 键 e:{摘要前20字符}
 * 汇总：GET /stats?token=xxx
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // 防御检查：绑定/密钥缺失时直接告知
    if (!env.PING_KV) {
      return Response.json({ error: "PING_KV binding missing", hint: "Settings → Bindings 添加 KV Namespace，变量名必须为 PING_KV" }, { status: 500 });
    }

    if (url.pathname === "/stats") {
      if (url.searchParams.get("token") !== env.STATS_TOKEN) {
        return new Response("Not Found", { status: 404 });
      }
      const buckets = { v: "byVersion", f: "byFunc", e: "byError" };
      const out = { total: 0, byVersion: {}, byFunc: {}, byError: {}, since: "" };
      for (const [prefix, field] of Object.entries(buckets)) {
        let cursor;
        do {
          const page = await env.PING_KV.list({ prefix: `${prefix}:`, cursor });
          for (const key of page.keys) {
            const n = Number(await env.PING_KV.get(key.name)) || 0;
            out[field][key.name.slice(prefix.length + 1)] = n;
            if (prefix === "v") out.total += n;
          }
          cursor = page.list_complete ? undefined : page.cursor;
        } while (cursor);
      }
      out.since = (await env.PING_KV.get("meta:since")) || "";
      return Response.json(out);
    }

    const version = sanitize(url.searchParams.get("v") || "");
    const func = sanitize(url.searchParams.get("func") || "");
    const errorTag = sanitize(url.searchParams.get("error") || "");

    // 计数键：功能埋点 > 错误 > 普通版本启动
    let key;
    if (func) {
      key = `f:${func}:${version || "dev"}`;
    } else if (errorTag) {
      key = `e:${errorTag}`;
    } else if (version) {
      key = `v:${version}`;
    } else {
      return new Response("ok", { headers: { "Access-Control-Allow-Origin": "*" } });
    }

    const current = Number(await env.PING_KV.get(key)) || 0;
    await env.PING_KV.put(key, String(current + 1));
    if (!(await env.PING_KV.get("meta:since"))) {
      await env.PING_KV.put("meta:since", new Date().toISOString().slice(0, 10));
    }
    return new Response("ok", { headers: { "Access-Control-Allow-Origin": "*" } });
  },
};

/** 白名单字符消毒（防 KV 键注入），错误摘要额外截断 */
function sanitize(v, maxLen = 20) {
  return v.replace(/[^a-zA-Z0-9.\-_]/g, "_").slice(0, maxLen) || "";
}
