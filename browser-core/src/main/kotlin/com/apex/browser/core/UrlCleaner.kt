package com.apex.browser.core

/*
 * UrlCleaner —— 跟踪参数剥离与 URL 卫生化（v1.7.0）。
 *
 * 为什么存在：现代站点分享/跳转 URL 普遍携带 30~50 个已知跟踪参数
 *（utm_* / fbclid / gclid / msclkid …）。浏览器「复制链接」「加入书签」
 * 把这些参数原样固化，等于替分析平台记账。业界基线（Brave / Firefox
 * 「复制干净链接」/ 1Blocker URL Cleaner）均已内置同类能力。
 *
 * 设计约束：
 * 1. **纯 JVM、零 Android 依赖**——与 [DomParser] 同级，进 `:browser-core`
 *    可单测；
 * 2. **手工解析而非 [java.net.URI]**——真实世界 URL 大量含未转义中文 /
 *    空格 / 未规范 percent-encoding，`URI()` 直接抛
 *    `IllegalArgumentException` 的概率不可忽视（分享链接最常见的形态
 *    恰恰是这类），失败面必须为零而不是「尽力而为」；
 * 3. **只删不改**——保留 scheme/host/path/fragment 与参数顺序，绝不
 *    重排序、不补默认端口、不动大小写。重写 URL 是行为变更，用户粘贴
 *    到别处一旦不等价就是静默事故；
 * 4. **幂等**——clean(clean(x)) == clean(x)。已清理过的 URL 再次清理
 *    零变化（书签去重依赖该性质）。
 *
 * 白名单口径：只删「公认跟踪参数」+ 空值参数；`ref` / `source` / `id`
 * 这类语义两可的参数**不删**（大量站点把它们当功能性参数用，误删即断链）。
 */
object UrlCleaner {

    /**
     * 公认跟踪参数（小写匹配）。口径：
     * - Google Analytics：utm_* 前缀族 + gclid / gclsrc / dclid / gbraid / wbraid；
     * - Meta：fbclid / fb_action_ids / fb_action_types / fb_ref / fb_source；
     * - Microsoft：msclkid；
     * - Mailchimp：mc_eid / mc_cid；
     * - Instagram / TikTok / 小红书：igshid / igsh / ttclid / xsec_token*；
     * - Hubspot / Vero / Yahoo / Yandex / 亚马逊 tracking 遗留：_hsenc /
     *   _hsmi / vero_id / vero_conv / yclid / _openstat / ym_uid / _ym_uid /
     *   ref_ / pd_rd_*；
     * - 双 11 大促常见 share 追踪：spm / scm / share_token / scene*。
     *（`*` 结尾表示按前缀匹配。）
     */
    private val TRACKING_EXACT: Set<String> = setOf(
        // Google Ads / Analytics
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "gad_campaign",
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_source_platform", "utm_creative_format",
        "utm_marketing_tactic",
        // Meta
        "fbclid", "fb_action_ids", "fb_action_types", "fb_ref", "fb_source",
        "mibextid",
        // Microsoft
        "msclkid",
        // Mailchimp
        "mc_eid", "mc_cid",
        // Instagram
        "igshid", "igsh", "ig_mid",
        // TikTok
        "ttclid",
        // Hubspot / Vero / Braze
        "_hsenc", "_hsmi", "vero_id", "vero_conv", "vgo_ee", "braze_id",
        // Yahoo / Yandex / OpenStat
        "yclid", "_openstat", "ym_uid", "_ym_uid", "_ym_isad", "ysclid",
    ).toSet()

    /** 前缀匹配的跟踪参数族（以 `*` 视为前缀通配）。 */
    private val TRACKING_PREFIXES: List<String> = listOf(
        "utm_",            // Analytics 族（含三方扩展 utm_xxx）
        "hsa_",            // Hubspot Ads
        "pk_",             // Matomo
        "mtm_",            // Matomo Tag Manager
        "matomo_",         // Matomo 长名
        "piwik_",          // Matomo 旧名
        "dm_",             // Alibaba double11 埋点
        "scm",             // 淘系链路追踪（scm / scm_id）
        "spm",             // 淘系位置埋点（spm / spm_id）
        "xsec_",           // 小红书安全令牌追踪（分享链接）
        "share_",          // 分享追踪（share_token / share_medium…）
        "scene",           // 小程序/分享场景值
        "pd_rd_",          // 亚马逊推荐位（复制场景属跟踪遗留）
        "pscdr",           // 亚马逊个性化推荐
    )

    /** 一次 clean 里最多剔除的参数个数上限（防御性：畸形 URL 不应产出惊人体积的删除清单）。 */
    private const val MAX_REMOVED_REPORT = 32

    /**
     * 判定单个参数名是否跟踪参数。
     * @param name 原始参数名（= 左侧），大小写不敏感
     */
    fun isTrackingParam(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return false
        if (n in TRACKING_EXACT) return true
        return TRACKING_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * 剥离跟踪参数与空值参数，返回干净 URL。
     *
     * 规则（按优先级）：
     * 1. **fragment 先于 query 定界**（WHATWG 口径）：`#` 之后整体是
     *    fragment——`?a=1#frag` 的 query 是 `a=1`、fragment 是 `#frag`；
     *    反过来 `#frag?x=1` 里的 `?x=1` 属 fragment **不是 query**。旧版
     *    按「首个 `?` 切」实现，fragment 落进最后一个参数的值里被连带
     *    剔除（`?b=2&utm_term=k#sec-3` 丢 `#sec-3`）——真实修复；
     * 2. query 逐段解析（`&` 分割，取首个 `=` 为键值边界——`a=b=c` 的值是
     *    `b=c`）；跟踪参数（[isTrackingParam]）与**空值参数**（`k=`）剔除；
     *    裸键参数（`k` 无 `=`）保留——不少站点把裸布尔键当开关；
     * 3. fragment 原样保留——fragment 属页面内状态不属于跟踪面（个别站点
     *    在 fragment 放 utm 属于自伤，不替它做主）；
     * 4. 剔除后 query 为空 → 整个 `?` 一起移除（不留悬挂 `?`）；
     * 5. 解析全程 runCatching 兜底：任何畸形输入返回原串（**绝不因清理而断链**）。
     */
    fun clean(url: String): String {
        if (url.isBlank()) return url
        return runCatching {
            // fragment 先定界（见 KDoc 规则 1）
            val hashIdx = url.indexOf('#')
            val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
            val noFrag = if (hashIdx >= 0) url.substring(0, hashIdx) else url
            val qIdx = noFrag.indexOf('?')
            if (qIdx < 0) return noFrag + fragment
            val head = noFrag.substring(0, qIdx)
            val query = noFrag.substring(qIdx + 1)
            val kept = ArrayList<String>(8)
            for (rawPair in query.split('&')) {
                if (rawPair.isEmpty()) continue
                val eq = rawPair.indexOf('=')
                val name = if (eq >= 0) rawPair.substring(0, eq) else rawPair
                val value = if (eq >= 0) rawPair.substring(eq + 1) else null
                if (isTrackingParam(name)) continue          // 跟踪参数 → 剔除
                if (value != null && value.isEmpty()) continue // 空值参数 → 剔除
                kept.add(rawPair)
            }
            if (kept.isEmpty()) head + fragment
            else head + "?" + kept.joinToString("&") + fragment
        }.getOrDefault(url)
    }

    /** [clean] 的可空形态：输入不可解析/无变化时返回 null（调用方据此免发「已清理」轻提示）。 */
    fun cleanOrNull(url: String): String? {
        val cleaned = clean(url)
        return if (cleaned == url) null else cleaned
    }

    /**
     * 计算被剔除的参数名清单（供「已去除 N 个跟踪参数」文案使用）。
     *
     * 输出保持 URL 中的出现顺序、去重、上限 [MAX_REMOVED_REPORT]。
     * 解析失败返回空清单（与 [clean] 的兜底语义一致：清理器永不抛错）。
     */
    fun removedParams(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return emptyList()
        return runCatching {
            val seen = LinkedHashSet<String>()
            for (rawPair in url.substring(qIdx + 1).split('&')) {
                if (rawPair.isEmpty()) continue
                val eq = rawPair.indexOf('=')
                val name = if (eq >= 0) rawPair.substring(0, eq) else rawPair
                if (isTrackingParam(name)) seen.add(name)
                if (seen.size >= MAX_REMOVED_REPORT) break
            }
            seen.toList()
        }.getOrDefault(emptyList())
    }

    /**
     * 提取主机名（供书签/轻提示展示；去掉 scheme、userinfo、端口与 `www.` 前缀）。
     *
     * 手工实现而非 [java.net.URI]：与 [clean] 同一条失败面约束——畸形输入
     * 返回原串或空串，不抛异常。userinfo（`user:pw@`）经 `substringAfter('@')`
     * 剥离——无 `@` 时回退整段 authority（`substringBefore('@')` 会把 userinfo
     * 误当 host，真实修复）。
     */
    fun hostOf(url: String): String {
        if (url.isBlank() || !url.contains("://")) return ""
        val noScheme = url.substringAfter("://", url)
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfter('@', missingDelimiterValue = authority).substringBefore(':')
        return host.removePrefix("www.").lowercase()
    }

    /**
     * 归一化 URL 用作书签去重键：`clean` + 去默认端口 + 末尾斜杠折叠 +
     * scheme 小写 + host 小写 + **fragment 剥离**（同一文章的不同章节
     * `#section` 折叠为同一条书签——书签是「页面」级概念）。
     *
     * 注意：**不**做 percent-decode——`/a%20b` 与 `/a b` 在部分服务端语义
     * 不同，折叠它们属于行为变更；也**不**折叠 http:80 与 https——二者是
     * 不同来源（不同 TLS 边界）。
     */
    fun normalizeKey(url: String): String {
        val cleaned = clean(url)
        return runCatching {
            val noFrag = cleaned.substringBefore('#')
            val hasScheme = noFrag.contains("://")
            val scheme = if (hasScheme) noFrag.substringBefore("://").lowercase() else ""
            val body = if (hasScheme) noFrag.substringAfter("://") else noFrag
            val authority = body.substringBefore('/')
            val pathAndMore = body.substringAfter('/', "")
            val hostRaw = authority.substringAfter('@', missingDelimiterValue = authority).substringBefore(':')
            val port = authority.substringAfter(':', missingDelimiterValue = "")
            val host = hostRaw.removePrefix("www.").lowercase()
            val path = if (pathAndMore.isEmpty()) "" else "/$pathAndMore"
            val trimmedPath = if (path.length > 1) path.trimEnd('/') else path
            val portPart = when {
                port.isEmpty() -> ""
                (scheme == "http" && port == "80") -> ""
                (scheme == "https" && port == "443") -> ""
                else -> ":$port"
            }
            buildString {
                if (scheme.isNotEmpty()) append(scheme).append("://")
                append(host).append(portPart).append(trimmedPath)
            }
        }.getOrDefault(cleaned)
    }
}
