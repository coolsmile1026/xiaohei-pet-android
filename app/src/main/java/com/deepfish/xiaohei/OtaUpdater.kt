package com.deepfish.xiaohei

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OTA 自更新。整体照搬 Switch-HA 的 source/update.c 思路，换成 Android 的 APK：
 *
 *  - manifest 默认 GitHub Releases `releases/latest`（tag_name = 版本，assets 里第一个 .apk = 下载地址）；
 *    备用通道是仓库根目录的 update.json（自托管 JSON 格式），GItHub API 挂了也能用。
 *  - 国内网络重点：manifest 和整包下载都带一组镜像代理前缀，检查/下载前先并发竞速
 *    （各探 64KB，谁先到用谁），不再按顺序死等直链；下载中还有低速熔断。
 *  - 版本比较逐段数字，忽略 v 前缀和后缀（1.10 > 1.9，1.0 == 1.0.0）。
 *  - 下载到 cacheDir，校验 ZIP 头、包可解析、版本匹配、签名与本机一致，再交给系统安装器。
 *
 * 换源：filesDir/ota.txt
 *   第一行 manifest URL（# 开头是注释）
 *   mirror: https://xxx/   追加镜像前缀（写了就替换内置列表）
 *   mirror: none           关掉内置镜像
 */
object OtaUpdater {

    private const val TAG = "XiaoheiOta"
    const val REPO = "coolsmile1026/xiaohei-pet-android"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RAW_JSON = "https://raw.githubusercontent.com/$REPO/main/update.json"

    /** 与 Switch-HA 的 UPD_MIRROR 一致，实测可用的 GitHub 反代前缀 */
    private val DEFAULT_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh-proxy.net/",
        "https://ghfile.geekertao.top/",
        "https://ghproxy.net/"
    )

    private const val PROBE_BYTES = 64 * 1024
    private const val MAX_APK = 300L * 1024 * 1024

    data class Config(val manifest: String, val mirrors: List<String>, val mirrorsOff: Boolean)
    data class Info(
        val version: String,
        val url: String,
        val alt: String,
        val notes: String,
        val size: Long,
        val source: String
    )

    // ---------------------------------------------------------------- 版本

    fun currentVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    /** 逐段数字比较，忽略 v 前缀与非数字后缀；1.0 与 1.0.0 视为相等 */
    fun compare(a: String, b: String): Int {
        val sa = a.trim().trimStart('v', 'V').split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val sb = b.trim().trimStart('v', 'V').split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val x = sa.getOrNull(i)?.toLongOrNull() ?: 0
            val y = sb.getOrNull(i)?.toLongOrNull() ?: 0
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    // ---------------------------------------------------------------- 配置 / 状态（跨进程走 filesDir）

    fun config(c: Context): Config {
        val f = File(c.filesDir, "ota.txt")
        if (!f.isFile) return Config(API_LATEST, DEFAULT_MIRRORS, false)
        val lines = try {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) {
            emptyList()
        }
        var manifest: String? = null
        var off = false
        val mir = mutableListOf<String>()
        for (line in lines) {
            when {
                line.startsWith("mirror:", true) -> {
                    val v = line.substringAfter("mirror:").trim()
                    if (v.equals("none", true)) off = true
                    else if (v.startsWith("http")) mir += if (v.endsWith("/")) v else "$v/"
                }
                line.startsWith("http") && manifest == null -> manifest = line
                else -> Unit
            }
        }
        return Config(manifest ?: API_LATEST, if (mir.isEmpty()) DEFAULT_MIRRORS else mir, off)
    }

    private fun stateFile(c: Context) = File(c.filesDir, "pet_ota.state.json")

    fun readState(c: Context): JSONObject = try {
        JSONObject(stateFile(c).readText())
    } catch (_: Exception) {
        JSONObject()
    }

    /** 原子写，控制页在主进程、服务在 :pet，不能靠内存里的单例状态 */
    fun writeState(c: Context, o: JSONObject) {
        try {
            val f = stateFile(c)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) f.writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    private fun autoFile(c: Context) = File(c.filesDir, "pet_ota.auto")
    fun autoEnabled(c: Context): Boolean = autoFile(c).exists()
    fun setAuto(c: Context, on: Boolean) {
        try {
            if (on) autoFile(c).writeText("1") else autoFile(c).delete()
        } catch (_: Exception) {
        }
    }

    private fun skipFile(c: Context) = File(c.filesDir, "pet_ota.skip")
    fun skippedVersion(c: Context): String = try {
        skipFile(c).readText().trim()
    } catch (_: Exception) {
        ""
    }

    fun setSkipped(c: Context, v: String) {
        try {
            if (v.isEmpty()) skipFile(c).delete() else skipFile(c).writeText(v)
        } catch (_: Exception) {
        }
    }

    private fun checkFile(c: Context) = File(c.filesDir, "pet_ota.lastcheck")
    fun lastCheck(c: Context): Long = try {
        checkFile(c).readText().trim().toLongOrNull() ?: 0
    } catch (_: Exception) {
        0
    }

    fun markChecked(c: Context) {
        try {
            checkFile(c).writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(url: String, timeout: Int, extra: Map<String, String> = emptyMap()): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "xiaohei-pet-android")
        conn.setRequestProperty("Accept-Encoding", "identity")
        extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readText(url: String, maxBytes: Int, timeout: Int, extra: Map<String, String> = emptyMap()): String {
        val conn = open(url, timeout, extra)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val stream = conn.inputStream
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var left = maxBytes
            while (left > 0) {
                val n = stream.read(tmp, 0, minOf(tmp.size, left))
                if (n < 0) break
                buf.write(tmp, 0, n)
                left -= n
            }
            return String(buf.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** 并发跑同一件事，取第一个成功结果；比按固定顺序串行试错快得多 */
    private fun <T> race(items: List<String>, waitMs: Long, work: (String) -> T?): Pair<Int, T>? {
        if (items.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(items.size, 8))
        val queue = LinkedBlockingQueue<Pair<Int, T>>()
        try {
            items.forEachIndexed { i, it ->
                pool.execute {
                    try {
                        val r = work(it) ?: return@execute
                        queue.offer(i to r)
                    } catch (e: Exception) {
                        Log.d(TAG, "race [$i] ${it.take(70)}: ${e.message}")
                    }
                }
            }
            return queue.poll(waitMs, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- manifest

    private fun parseManifest(body: String): Info? {
        val t = body.trim()
        if (!t.startsWith("{")) {
            val ls = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (ls.size >= 2 && ls[1].startsWith("http")) {
                return Info(ls[0], ls[1], "", "", 0, "text")
            }
            return null
        }
        val o = try {
            JSONObject(t)
        } catch (_: Exception) {
            return null
        }
        // GitHub Releases API
        val tag = o.optString("tag_name", "")
        if (tag.isNotEmpty()) {
            val arr = o.optJSONArray("assets") ?: return null
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                if (!a.optString("name", "").endsWith(".apk", true)) continue
                val dl = a.optString("browser_download_url", "")
                if (dl.isEmpty()) return null
                return Info(
                    tag, dl, a.optString("url", ""), o.optString("body", ""),
                    a.optLong("size", 0), "github"
                )
            }
            return null
        }
        // 自托管 JSON：{"version":"1.1","url":"...","notes":"...","size":...}
        val v = o.optString("version", "")
        val u = o.optString("url", "")
        if (v.isEmpty() || !u.startsWith("http")) return null
        return Info(v, u, o.optString("alt", ""), o.optString("notes", ""), o.optLong("size", 0), "json")
    }

    /**
     * manifest 候选：自定义/默认源直连 → 各镜像前缀 + 该源 → raw update.json 直连及其镜像。
     * 谁先返回一份能解析的 manifest 就用谁；GitHub API 被墙时镜像照样能查到新版。
     */
    fun fetchManifest(c: Context): Info? {
        val cfg = config(c)
        val urls = mutableListOf<String>()
        urls += cfg.manifest
        if (!cfg.mirrorsOff) cfg.mirrors.forEach { urls += it + cfg.manifest }
        if (cfg.manifest == API_LATEST) {
            urls += RAW_JSON
            if (!cfg.mirrorsOff) cfg.mirrors.forEach { urls += it + RAW_JSON }
        }
        Log.i(TAG, "manifest candidates ${urls.size}: ${urls.first()}")
        val winner = race(urls, 9000L) { u ->
            val body = readText(u, 256 * 1024, 6000)
            parseManifest(body)
        }
        if (winner == null) {
            Log.w(TAG, "all ${urls.size} manifest routes failed")
            return null
        }
        Log.i(TAG, "manifest winner [${winner.first}] ${urls[winner.first].take(90)} -> ${winner.second.version}")
        return winner.second
    }

    // ---------------------------------------------------------------- 下载

    /** 探测一条线路：只取前 64KB 就断开，Range 被忽略也不会把整个 100MB 拉下来 */
    private fun probe(url: String): Long {
        val conn = open(url, 3500, if (url.contains("/releases/assets/")) {
            mapOf("Accept" to "application/octet-stream")
        } else emptyMap())
        try {
            conn.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val s = conn.inputStream
            val tmp = ByteArray(8192)
            var left = PROBE_BYTES
            var got = 0
            while (left > 0) {
                val n = s.read(tmp, 0, minOf(tmp.size, left))
                if (n < 0) break
                left -= n
                got += n
            }
            // 探测至少要拿到一点真实数据，光返回 200 不算通
            if (got < 1024) throw IllegalStateException("probe got $got bytes")
            return got.toLong()
        } finally {
            conn.disconnect()
        }
    }

    private fun candidates(c: Context, info: Info): List<String> {
        val cfg = config(c)
        val list = mutableListOf(info.url)
        if (info.alt.startsWith("https://api.github.com/")) list += info.alt
        if (!cfg.mirrorsOff && info.url.startsWith("https://")) {
            cfg.mirrors.forEach { list += it + info.url }
        }
        return list.distinct()
    }

    /** 各线路同时探 64KB，2.5 秒内按耗时排序；全探不通就保持原顺序硬试 */
    private fun rankRoutes(urls: List<String>): List<String> {
        if (urls.size < 2) return urls
        val pool = Executors.newFixedThreadPool(minOf(urls.size, 8))
        val cost = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val done = java.util.concurrent.CountDownLatch(urls.size)
        try {
            urls.forEachIndexed { i, u ->
                pool.execute {
                    val t0 = System.currentTimeMillis()
                    try {
                        probe(u)
                        cost[i] = System.currentTimeMillis() - t0
                    } catch (e: Exception) {
                        Log.d(TAG, "probe [$i] fail ${e.message}: ${u.take(80)}")
                    } finally {
                        done.countDown()
                    }
                }
            }
            // 全通就立刻收工，否则最多等 2.5s，别让一条死线路拖住整包下载
            done.await(2500, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        val rank = cost.entries.sortedBy { it.value }.map { urls[it.key] }
        val rest = urls.filter { !rank.contains(it) }
        Log.i(TAG, "race winner ${cost.entries.minByOrNull { it.value }?.key ?: -1}/${urls.size}, ${rank.size} routes alive")
        return rank + rest
    }

    /**
     * 下载整包。progress(百分比, 已下载, 总大小) 由调用方切回主线程更新 UI。
     * 低速熔断：跑满 12 秒还不到 64KB 就换下一条线路，不在死线路上干等。
     */
    fun download(
        c: Context,
        info: Info,
        progress: (Int, Long, Long) -> Unit
    ): File? {
        val urls = rankRoutes(candidates(c, info))
        val dir = File(c.cacheDir, "ota")
        dir.mkdirs()
        val part = File(dir, "download.part")
        for ((i, u) in urls.withIndex()) {
            Log.i(TAG, "dl [$i]/${urls.size} ${u.take(100)}")
            try {
                try {
                    if (part.exists()) part.delete()
                } catch (_: Exception) {
                }
                val conn = open(
                    u, 15000,
                    if (u.contains("/releases/assets/")) mapOf("Accept" to "application/octet-stream") else emptyMap()
                )
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val total = conn.contentLength.toLong().let { if (it > 0) it else info.size }
                    val t0 = System.currentTimeMillis()
                    var done = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done > MAX_APK) throw IllegalStateException("APK 超过 300MB")
                                if (System.currentTimeMillis() - t0 > 12000 && done < PROBE_BYTES) {
                                    throw IllegalStateException("线路太慢，熔断换线")
                                }
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress(pct.coerceIn(0, 99), done, total)
                                }
                            }
                            out.fd.sync()
                        }
                    }
                    Log.i(TAG, "dl [$i] ok ${done}B in ${(System.currentTimeMillis() - t0) / 1000}s")
                    if (!verify(c, part, info)) throw IllegalStateException("下载包校验不通过")
                    val apk = apkFile(c)
                    if (apk.exists()) apk.delete()
                    if (!part.renameTo(apk)) {
                        part.copyTo(apk, overwrite = true)
                        part.delete()
                    }
                    progress(100, done, total)
                    return apk
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "dl [$i] failed: ${e.message}")
            }
        }
        return null
    }

    fun apkFile(c: Context): File = File(c.cacheDir, "ota/xiaohei-pet.apk")

    /** 已下好且确实是本机可装的新版本时才返回，避免把上次残留的旧包当成新包 */
    fun readyApk(c: Context, version: String): File? {
        val f = apkFile(c)
        if (!f.isFile || !verify(c, f, Info(version, "", "", "", f.length(), "local"))) return null
        return f
    }

    private fun verify(c: Context, f: File, info: Info): Boolean {
        if (!f.isFile || f.length() < 1024 * 1024) return false
        // ZIP / APK 魔数
        val head = f.inputStream().use { ByteArray(4).also { b -> it.read(b) } }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
        if (info.size > 0 && f.length() < info.size * 9 / 10) return false
        val pm = c.packageManager
        val pi = try {
            pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return false
        } catch (_: Exception) {
            return false
        }
        if (pi.packageName != c.packageName) return false
        if (info.version.isNotEmpty() && compare(pi.versionName ?: "", info.version) < 0) return false
        return sameSignature(c, f)
    }

    /** 签名不一致系统会拒装（除非先卸载），提前判掉，别让用户白等一次安装 */
    private fun sameSignature(c: Context, f: File): Boolean {
        return try {
            val pm = c.packageManager
            val mine: Array<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= 28) {
                    pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
                }
            val other: Array<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= 28) {
                    pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                        ?.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
                }
            if (mine.isNullOrEmpty() || other.isNullOrEmpty()) return true // 取不到就不拦
            mine.any { a -> other.any { b -> a.hashCode() == b.hashCode() } }
        } catch (_: Exception) {
            true
        }
    }

    // ---------------------------------------------------------------- 安装

    /** 走系统安装器 UI；APK 通过自家 ApkProvider 以 content:// 暴露，避免 Android 7+ 的 FileUriExposed */
    fun install(c: Context, f: File): Boolean {
        if (!f.isFile) return false
        val uri = Uri.parse("content://${c.packageName}.apk/ota/xiaohei-pet.apk")
        val base = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val candidates = listOf(
            base,
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        for (i in candidates) {
            i.clipData = ClipData.newUri(c.contentResolver, "xiaohei-update", uri)
            val resolved = try {
                c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) {
                null
            }
            if (resolved == null) continue
            return try {
                c.startActivity(i)
                Log.i(TAG, "install intent -> ${resolved.activityInfo.packageName}")
                true
            } catch (e: Exception) {
                Log.w(TAG, "install start failed: ${e.message}")
                false
            }
        }
        Log.w(TAG, "no installer activity on this ROM")
        return false
    }
}
