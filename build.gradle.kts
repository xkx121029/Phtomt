import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    // Shizuku server 内嵌（third_party/shizuku）所需的 hidden API 精化插件
    alias(libs.plugins.rikka.refine) apply false
}

// ---------------------------------------------------------------------------
// 版本号：在 root 统一计算，:app 与 :provider 只读取同一个值。
//
// 之前 app 用 git 提交数当 versionCode，导致「不提交新 commit 就永远不递增」，
// 每次构建出来的 APK 版本号都一样。现在改为：
//   1. `-PbuildNumber=NN` 优先（CI 注入，可用于覆盖/回退）；
//   2. 否则取 version.properties 里记录的上次值（首次以 git 提交数为基线），
//      只要本次调用会产出 APK（assemble / bundle / build），就 +1；
//      **回写发生在 package 任务成功之后**（见下方 packaging 任务的 doLast），失败构建不消费版本号。
//
// 为什么放在 root：Gradle 会先配置 root 再配置子项目，`org.gradle.parallel=true`
// 下 app/provider 的配置顺序不保证，各自算一遍会算出不同值（两边版本号错位）。
// ---------------------------------------------------------------------------
val versionPropsFile = rootProject.file("version.properties")

fun readStoredBuildNumber(): Int {
    if (!versionPropsFile.exists()) return 0
    val props = Properties().apply {
        runCatching { versionPropsFile.inputStream().use { load(it) } }
    }
    return props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 0
}

fun gitCommitCount(): Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

// 只有真正产出 APK 的调用才自增：跑 detekt / test / syncProviderAssets 时
// 不该平白吃掉一个版本号。androidPackage 是可选模块里的"构建 + 归档"任务，
// 它依赖 :app:assembleDebug，同样会产出 APK，必须计入自增判断。
val apkBuilding = run {
    val names = gradle.startParameter.taskNames.map { it.lowercase(Locale.ROOT).substringAfterLast(':') }
    names.any {
        it == "build" || it.contains("assemble") || it.contains("bundle") || it == "androidpackage"
    }
}

val injectedBuildNumber = (findProperty("buildNumber") as? String)?.toIntOrNull()

val buildNumber: Int = run {
    val stored = readStoredBuildNumber()
    // 基线取「已记录值」与「git 提交数」的较大者：即使期间新增了 commit，
    // 版本号也只增不减（versionCode 回退会导致覆盖安装失败）。
    val base = maxOf(stored, gitCommitCount(), 0)
    val value = injectedBuildNumber ?: (if (apkBuilding) base + 1 else maxOf(base, 1))
    maxOf(value, 1)
}

// 版本号回写：只有真正产出 APK/AAB 的 package* 任务**成功之后**才写（见下方子项目 configureEach
// 里的 doLast）。配置阶段就写的后果是：`--dry-run`、detekt/test、以及编译失败的构建都会吃掉
// 一个版本号，并且让被跟踪的 version.properties 每次构建后都变脏。
// 写成内联代码而不是脚本里的函数——任务动作不能引用脚本对象（配置缓存限制，见下方构建锁说明）。

// 子项目通过 rootProject.extra["buildNumber"] 读取
extra["buildNumber"] = buildNumber

// ===========================================================================
// 项目级构建锁（单构建槽位）
//
// 为什么需要：同一工作区经常有多条线同时跑 Gradle。实测过三种互相踩的方式——
//   1. 两个进程写同一个 app/build/tmp/kotlin-classes，报 "Could not delete .../com"；
//   2. 一个构建把另一个的 Gradle/Kotlin daemon 停掉，报 "daemon disappeared"；
//   3. tool/bundle_host.mjs 重写 assets/agent-host 时被并行的 mergeAssets 占用，
//      Windows 上 rename 直接 EPERM。
// 这些不是某个任务的 bug，而是「同一 checkout 只允许一个构建在动共享产物」。
//
// 做法：给会互相踩的任务加一把文件锁（.gradle/build.lock），拿不到就**立刻报错并给出
// 持有者信息**——不静默重试、不排队，避免把一次构建拖一整晚。
// 被锁的任务：bundleHost（重写 assets）、merge*Assets（读 assets）、package*（写 APK/AAB）。
//
// 注意：这里刻意只锁「任务执行窗口」，不锁整个构建——配置阶段仍然可以并行，
// 否则一个跑单测的构建会把所有打包构建堵死。
// ===========================================================================
fun isBuildLockTask(name: String): Boolean =
    name == "bundleHost" ||
        name == "bundleDebug" ||
        name == "bundleRelease" ||
        (name.startsWith("merge") && name.endsWith("Assets")) ||
        // 编译才是真正的碰撞点：kotlin-classes 与 build/kotlin/.../caches-jvm 会被两个构建
        // 同时写；Kotlin daemon 回退进程内重编时第一步就是清 kotlin-classes，清不掉就整个构建失败。
        (name.startsWith("compile") && name.endsWith("Kotlin")) ||
        name.startsWith("package")

// 实现约束（改之前必须读）：
//   配置缓存（org.gradle.configuration-cache，可能在本机/CI 被打开）不允许任务动作引用
//   **脚本对象**——脚本级 val、脚本里的顶层函数都不行，否则报
//   "cannot serialize Gradle script object references"，整个构建在存储缓存时失败。
//   因此下面所有 doFirst/doLast 只捕获配置期的局部 String/Int/File，逻辑全部内联。
//   「谁还持有锁」这类跨任务/跨项目的状态不放内存，而是写成
//   .gradle/build.lock.d/<project>__<task> 标记文件：多个项目并行释放时也成立。
//
// 兼容的历史坑（别退回去）：
//   - gradle.taskGraph.afterTask(...) → 配置缓存不支持注册任务图监听器，直接构建失败；
//   - 在 configureEach 里 tasks.register(...) → "cannot be executed in the current context"。
// ===========================================================================

// ---------------------------------------------------------------------------
// 锁释放服务：Gradle 在构建结束时（成功、失败、任务抛异常）都会关闭 BuildService。
// 这是唯一可靠的收尾点——不能用 finalizedBy 的任务释放，因为「一个 task 一次构建只执行一次」，
// 释放任务在第一个被锁任务结束后就执行完了，后续任务再取锁就没人释放（实测编译任务长时间持锁）。
// 进程被外部杀掉时 close() 不会执行，这种情况由取锁时的「pid 存活探测 + 60 分钟阈值 + 无标记孤儿锁」兜底。
// ---------------------------------------------------------------------------
interface BuildLockReleaseParams : BuildServiceParameters {
    val lockPath: Property<String>
    val markerDir: Property<String>
    val pid: Property<Long>
}

abstract class BuildLockReleaseService : BuildService<BuildLockReleaseParams>, AutoCloseable {
    override fun close() {
        val lock = File(parameters.lockPath.get())
        val markerDir = File(parameters.markerDir.get())
        val selfPid = parameters.pid.get()
        val firstLine = runCatching { lock.readLines().firstOrNull() }.getOrNull()
        // 诊断输出走 stdout：BuildService 里没有 logger，println 会进 daemon 日志。
        // 只在本服务确实参与过构建时打印，正常构建多一行无害，出事时能一眼看出收尾有没有跑。
        println("[build-lock] release-service close：self=pid=$selfPid，锁首行=${firstLine ?: "(无锁)"}")
        // 只释放「确实由本进程持有」的锁：别人的锁（以及它同名标记）一概不动。
        if (firstLine != "pid=$selfPid") return
        markerDir.listFiles()?.forEach { it.delete() }
        var waited = 0
        while (waited < 20 && markerDir.listFiles()?.isNotEmpty() == true) {
            Thread.sleep(50)
            waited++
        }
        val left = markerDir.listFiles()?.size ?: 0
        val removed = if (left == 0) lock.delete() else false
        println("[build-lock] release-service 已释放：lock=$removed，剩余标记=$left")
    }
}

val buildLockRelease = gradle.sharedServices.registerIfAbsent(
    "hpaBuildLockRelease",
    BuildLockReleaseService::class.java,
) {
    parameters.lockPath.set(rootProject.file(".gradle/build.lock").absolutePath)
    parameters.markerDir.set(rootProject.file(".gradle/build.lock.d").absolutePath)
    parameters.pid.set(ProcessHandle.current().pid())
}

subprojects.forEach { p ->
    val lockFile = rootProject.file(".gradle/build.lock")
    val markerRoot = rootProject.file(".gradle/build.lock.d")
    val hostAssets = rootProject.file("app/src/main/assets/agent-host")
    val versionFile = versionPropsFile
    // 陈旧阈值：默认 60 分钟，必须高于「全量编译最坏情况」——实测 :app:compileDebugKotlin
    // 20–35 分钟，冷 daemon 再加约 9 分钟。阈值设成几分钟会把正常的长构建误判成死锁，
    // 那是比「假锁堵住」更坏的失败模式。可用 -Phpa.lockStaleMinutes=NN 覆盖。
    val staleMinutes = (findProperty("hpa.lockStaleMinutes") as? String)
        ?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 60
    val staleMs = staleMinutes * 60_000L
    val pid = ProcessHandle.current().pid()
    // 进程名只用于锁记录（出事时能看出是谁占的）；取不到就退回 "java"，不能因此写不出完整记录。
    val processName = runCatching {
        ProcessHandle.current().info().command().orElse("")
    }.getOrDefault("").let { if (it.isBlank()) "java" else File(it).name }
    // 主机名用来区分「本机 pid 可探测」和「跨机器只能靠超时」。优先读环境变量，避免配置阶段做 DNS 解析。
    val hostName = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "unknown"
    val user = System.getProperty("user.name").orEmpty()
    val projectKey = p.path.removePrefix(":").replace(':', '_')
    val number = buildNumber
    val injected = injectedBuildNumber
    val buildsApk = apkBuilding

    // 释放不再用「一个 finalizedBy 的释放任务」：
    // Gradle 里一个 task 一次构建只执行一次，释放任务在第一个被锁任务结束后就跑完了，
    // 之后的任务再取锁就没有人释放——实测表现就是 :app:compileDebugKotlin 从 06:07 起一直持锁。
    // 现在由 BuildLockReleaseService 统一收尾：构建结束（成功/失败/编译回退）都会调 close()。
    p.tasks.matching { isBuildLockTask(it.name) }.configureEach {
        val owner = path
        val taskName = name
        // 只有真正产出 APK/AAB 的任务才算「消费版本号」。不能把 package*Resources / package*Assets
        // 也算进来：它们先于打包任务运行，写回之后同一个构建里的 packageDebug 会把自己刚写的号
        // 判成「已被占用」，assembleDebug 每次都失败。
        val consumesVersion = taskName == "packageDebug" || taskName == "packageRelease" ||
            taskName == "packageDebugAndroidTest" || taskName == "packageReleaseAndroidTest" ||
            taskName == "bundleDebug" || taskName == "bundleRelease" ||
            (taskName.startsWith("package") && taskName.endsWith("Bundle"))
        val mergingAssets = taskName.startsWith("merge") && taskName.endsWith("Assets") && p.path == ":app"
        val markerPath = File(markerRoot, "${projectKey}__$taskName").absolutePath
        val markerDirPath = markerRoot.absolutePath
        val lockPath = lockFile.absolutePath
        val localStaleMs = staleMs
        val localStaleMinutes = staleMinutes
        val localPid = pid
        val localProcessName = processName
        val localHost = hostName
        val localUser = user
        val localNumber = number
        val localInjected = injected
        val localVersionFile = versionFile.absolutePath
        // 「这个版本号是我自己刚写的」凭据。不能只看 version.properties 的值：
        // assemble 会先后跑 packageDebug 和 packageRelease，第二个任务必须能认出第一个的写回。
        val localVersionMarker = rootProject.file(".gradle/build-number.marker").absolutePath
        val localHostAssets = hostAssets.absolutePath
        // 声明使用释放服务：只有被任务声明使用的 BuildService 才会在构建结束时被关闭。
        usesService(buildLockRelease)
        // 光声明 usesService 不会实例化服务，而没被实例化的 BuildService 在构建结束时不会调 close()
        // ——这正是「锁一直不放」的原因。这里在配置期把注册对象抓成局部变量（只捕获局部值，
        // 不引用脚本对象），任务真跑起来时主动取一次实例，确保收尾路径一定存在。
        val releaseRegistration = buildLockRelease
        // 顺序很关键：先占槽位，再做版本号/资产校验——校验必须在锁内，
        // 否则校验通过之后、真正写 APK 之前仍可能被另一个构建改写。
        doFirst {
            // 主动实例化释放服务：懒加载的服务不取一次，构建结束时就不会有 close() 收尾。
            releaseRegistration.get()
            // ---------------- 1. 占住构建槽位 ----------------
            val lock = File(lockPath)
            val marker = File(markerPath)
            marker.parentFile.mkdirs()
            var owned = false
            if (lock.exists()) {
                // 锁记录必须完整：pid / process / task / started / host 缺一项，就无法判断持有者
                // 是不是还活着，一律按陈旧处理。05:03 那次事故正是死记录（pid=99999）把流水线
                // 无限期堵住且没有任何提示——旧实现既不探测 pid 存活，也没有可信的陈旧阈值。
                val record = runCatching { lock.readText() }.getOrNull()
                val fields = record?.lines()
                    ?.mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                    }
                    ?.toMap()
                    ?: emptyMap()
                val holderPid = fields["pid"]?.toLongOrNull()
                val holderHost = fields["host"]
                val holderTask = fields["task"]
                val holderProcess = fields["process"]
                val startedRaw = fields["started"]
                val startedAt = startedRaw?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                val ageMs = (startedAt?.let { Duration.between(it, OffsetDateTime.now()).toMillis() }
                    ?: (System.currentTimeMillis() - lock.lastModified())).coerceAtLeast(0)
                val sameHost = holderHost != null && holderHost.equals(localHost, ignoreCase = true)
                // ProcessHandle.of(pid) 等价于 kill(pid, 0)：只探测存在性，不发信号。
                // 跨主机时 pid 没有可比性，只能靠超时判断。
                val holderAlive = if (holderPid != null && sameHost) {
                    runCatching {
                        val handle = ProcessHandle.of(holderPid)
                        handle.isPresent && handle.get().isAlive
                    }.getOrDefault(false)
                } else {
                    false
                }
                val incomplete = holderPid == null || holderHost.isNullOrBlank() ||
                    holderTask.isNullOrBlank() || holderProcess.isNullOrBlank() || startedAt == null
                val expired = ageMs > localStaleMs
                // 锁文件还在、但一个持有标记都没有：说明上一次释放只删了标记、没删成锁
                // （或锁被手工留下）。给 2 分钟容差，避开「刚 createFile、还没写标记」的窗口。
                val holderMarkers = File(markerDirPath).listFiles()?.size ?: 0
                val orphaned = ageMs > 120_000 && holderMarkers == 0
                val ageDesc = when {
                    ageMs < 60_000 -> "${ageMs / 1000} 秒"
                    ageMs < 3_600_000 -> "${ageMs / 60_000} 分钟"
                    else -> "${ageMs / 3_600_000} 小时 ${(ageMs % 3_600_000) / 60_000} 分钟"
                }
                val staleReason = when {
                    record == null -> "锁文件读不出来（可能正在被删除或已损坏）"
                    incomplete -> "锁记录不完整，缺 pid / process / task / started / host 中的项" +
                        "（pid=${holderPid ?: "无"}，process=${holderProcess ?: "无"}，" +
                        "task=${holderTask ?: "无"}，started=${startedRaw ?: "无"}，host=${holderHost ?: "无"}）"
                    sameHost && !holderAlive -> "持有进程 pid=$holderPid 在本机已经不存在"
                    orphaned -> "锁文件存在但没有任何持有标记（上一次释放没有清干净）"
                    expired -> "持锁 $ageDesc，超过阈值 $localStaleMinutes 分钟" +
                        (if (holderAlive) "（该进程仍存活，可能已经卡死）" else "")
                    else -> null
                }
                if (staleReason == null && sameHost && holderPid == localPid) {
                    // 同一个 Gradle 进程：同一构建里的另一个被锁任务（--parallel），
                    // 或本 daemon 上一次构建崩溃留下的孤儿锁。一个 daemon 一次只跑一个构建，
                    // 所以并发的另一个构建不可能拿到同一个 pid，这里不会误放行。
                    owned = true
                } else if (staleReason != null) {
                    // 用 rename 抢占而不是 delete：rename 只有一个进程能成功，
                    // delete 可能擦掉别人刚建好的新锁，让两个进程同时「持有」。
                    val quarantine = File(lock.parentFile, "build.lock.stale-$localPid-${System.nanoTime()}")
                    if (lock.renameTo(quarantine)) {
                        val original = runCatching { quarantine.readText().trim() }.getOrDefault("")
                        quarantine.delete()
                        // 接管必须显式打印（不静默抢）：要能一眼看出「之前是谁、在跑什么任务、多久前」。
                        logger.warn(
                            "[build-lock] 锁被判定为陈旧并接管。\n" +
                                "  原因：$staleReason\n" +
                                "  原持有者：pid=${holderPid ?: "未知"}（${holderProcess ?: "进程名未知"}），" +
                                "任务=${holderTask ?: "未知"}，主机=${holderHost ?: "未知"}\n" +
                                "  开始于 ${startedRaw ?: "未知时间"}，距今 $ageDesc\n" +
                                "  原记录全文：\n" +
                                original.ifEmpty { "(空锁文件)" } + "\n" +
                                "  接管者：pid=$localPid（$localHost），任务 $owner",
                        )
                        // 旧持有者已判定死亡，它留下的持有标记也要清掉：
                        // 否则本构建结束时「标记目录非空」会让锁文件永远不释放——又是一个假锁。
                        File(markerDirPath).listFiles()?.forEach { it.delete() }
                    }
                }
            }
            if (!owned) {
                try {
                    Files.createFile(lock.toPath()) // 原子：并发的另一个构建会在这里失败
                } catch (e: FileAlreadyExistsException) {
                    val info = runCatching { lock.readText().trim() }.getOrDefault("")
                    fun lockField(key: String): String? = info.lineSequence()
                        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
                    val startedText = lockField("started")
                    val heldMs = startedText
                        ?.let { runCatching { Duration.between(OffsetDateTime.parse(it), OffsetDateTime.now()).toMillis() } }
                        ?.getOrNull()?.coerceAtLeast(0)
                    val heldDesc = when {
                        heldMs == null -> "时长未知"
                        heldMs < 60_000 -> "${heldMs / 1000} 秒"
                        heldMs < 3_600_000 -> "${heldMs / 60_000} 分钟"
                        else -> "${heldMs / 3_600_000} 小时 ${(heldMs % 3_600_000) / 60_000} 分钟"
                    }
                    throw GradleException(
                        "另一个构建正在执行会改动共享产物的任务" +
                            "（bundleHost / compile*Kotlin / merge*Assets / package*），本次构建在 $owner 处中止。\n" +
                            "持有者：pid=${lockField("pid") ?: "未知"}（${lockField("host") ?: "主机未知"}），" +
                            "任务=${lockField("task") ?: "未知"}，开始于 ${startedText ?: "未知"}，已持有 $heldDesc\n" +
                            "原始锁记录（${lock.absolutePath}）：\n$info\n" +
                            "这是「明确失败」，不是排队：本构建不会等待，也不会自动重试。\n" +
                            "给重试脚本的判据：看到本条消息就说明锁被别人持有，等它结束或 Ctrl+C 之后再跑；" +
                            "不要立刻循环重试，每次重试都会重新烧一遍编译。\n" +
                            "若持有进程已不存在，或已持有超过 $localStaleMinutes 分钟，" +
                            "下一次取锁会自动判定为陈旧并接管（同时打印原记录）；" +
                            "探测不到时也可以删除 ${lock.absolutePath}。"
                    )
                }
                lock.writeText(
                    "pid=$localPid\n" +
                        "process=$localProcessName\n" +
                        "task=$owner\n" +
                        "started=${OffsetDateTime.now().withNano(0)}\n" +
                        "host=$localHost\n" +
                        "user=$localUser\n"
                )
                logger.lifecycle("[build-lock] 获取成功：$owner（pid=$localPid，host=$localHost）")
            }
            marker.writeText(owner)

            // ---------------- 2. 打包前确认版本号没被别人用掉 ----------------
            // A、B 两个构建在配置阶段都读到 9142 并算出 9143，只靠「成功后回写」拦不住，
            // 所以在锁内、写 APK 之前再读一次磁盘。
            // 注意不能只判 stored >= localNumber：同一个构建里 assemble 会先后跑 packageDebug
            // 和 packageRelease，第一把成功回写后，第二把会把自己刚写的号当成冲突。
            // 因此用 .gradle/build-number.marker（pid + host + number）区分「自己写的」和「别人写的」。
            if (buildsApk && consumesVersion && localInjected == null) {
                val props = Properties()
                runCatching {
                    val f = File(localVersionFile)
                    if (f.exists()) f.inputStream().use { props.load(it) }
                }
                val stored = props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 0
                val markerText = runCatching { File(localVersionMarker).readText() }.getOrDefault("")
                fun markerField(key: String): String? = markerText.lineSequence()
                    .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
                val wroteByUs = markerField("pid") == localPid.toString() &&
                    markerField("host") == localHost &&
                    markerField("number") == localNumber.toString()
                if (stored > localNumber || (stored == localNumber && !wroteByUs)) {
                    throw GradleException(
                        "版本号 $localNumber 已被占用（version.properties 当前为 $stored，" +
                            "回写凭据=${if (markerText.isBlank()) "无" else "不属于本次构建"}）。\n" +
                            "另一个构建刚用掉了这个号；为避免两个 APK 拿到同一个 versionCode，" +
                            "本次打包在产出 APK 之前中止。直接重试即可拿到新号。"
                    )
                }
            }

            // ---------------- 3. 合并资源前确认 host 资产是完整快照 ----------------
            // bundle_host.mjs 在 Windows 上可能退化为「就地覆盖」，那几秒里目录是旧文件+新文件混合。
            // 别的构建来读已被 .gradle/build.lock 挡住；这条闸门挡的是「构建期间被手工/其它工具同步」。
            // 遍历顺序必须与 bundle_host.mjs 的 fingerprintOf 完全一致，否则指纹永远对不上。
            if (mergingAssets) {
                val dir = File(localHostAssets)
                val versionOnDisk = File(dir, "VERSION")
                if (versionOnDisk.isFile) {
                    val expected = runCatching { versionOnDisk.readText().trim() }.getOrDefault("")
                    val actual = runCatching {
                        val digest = MessageDigest.getInstance("SHA-256")
                        fun walk(d: File) {
                            for (f in (d.listFiles() ?: return).sortedBy { it.name }) {
                                if (f.name == "VERSION") continue
                                if (f.isDirectory) walk(f) else {
                                    digest.update(f.relativeTo(dir).invariantSeparatorsPath.toByteArray())
                                    digest.update(f.readBytes())
                                }
                            }
                        }
                        walk(dir)
                        // 显式按无符号字节拼 hex：`"%02x".format(Byte)` 在负数上是平台相关的，
                        // 指纹必须与 tool/bundle_host.mjs 的逐位结果一致，所以不做隐式转换。
                        digest.digest().joinToString("") {
                            (it.toInt() and 0xff).toString(16).padStart(2, '0')
                        }.take(16)
                    }.getOrNull()
                    if (expected.isEmpty() || actual != expected) {
                        throw GradleException(
                            "assets/agent-host 的内容与它自己的 VERSION 指纹不一致：" +
                                "VERSION=${expected.ifEmpty { "(空)" }}，实际内容=${actual ?: "(读取失败)"}。\n" +
                                "该目录很可能正处于被就地覆盖的中途（另一个进程正在同步 host）。" +
                                "此时 mergeAssets 会把半新半旧的 host 打进 APK，故中止。\n" +
                                "处理：等其它构建/同步结束后重试；或运行 `node tool/bundle_host.mjs` 重新同步一次。\n" +
                                "目录：${dir.absolutePath}"
                        )
                    }
                }
            }
        }
        if (buildsApk && consumesVersion) {
            doLast {
                // 只有 APK/AAB 真正打包成功后才消费版本号：失败的构建不烧号（下次复用同一个号）
                val f = File(localVersionFile)
                val props = Properties()
                runCatching { if (f.exists()) f.inputStream().use { props.load(it) } }
                val stored = props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 0
                if (localNumber > stored) {
                    f.writeText(
                        "#Auto-incremented by Gradle\n" +
                            "#" + ZonedDateTime.now()
                                .format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ROOT)) + "\n" +
                            "BUILD_NUMBER=$localNumber\n"
                    )
                }
                // 先写 version.properties、再写凭据：万一在这两步之间被杀，下一个构建会读到
                // 更大的号，不会与本次已产出的 APK 撞号（反过来的顺序才会造成重复 versionCode）。
                File(localVersionMarker).writeText(
                    "pid=$localPid\nhost=$localHost\nnumber=$localNumber\n" +
                        "written=${OffsetDateTime.now().withNano(0)}\n"
                )
            }
        }
        // 释放交给 BuildLockReleaseService：Gradle 在构建结束时（成功或失败）关闭 BuildService，
        // 这是比 finalizedBy 任务更可靠的收尾点——任务级的释放任务一次构建只执行一次，
        // 第一个被锁任务结束后它就跑了，后面再取锁没人释放（实测导致编译任务长时间持锁）。
    }
}

// ===========================================================================
// 可选构建模块（配置：config/build-modules.properties，说明：docs/build-modules.md）
//
// 设计约束（改这一块之前先读文档）：
//   1. 只"注册任务"，绝不把任何模块任务挂到 build / check / assemble 上——
//      所以模块开启或关闭都不改变默认构建流程的任务图、行为与耗时。
//   2. 每个模块独立读取自己的开关与参数，模块之间不建任务依赖；
//      唯一的构建型依赖是 androidPackage → :app:assembleDebug，属于该模块自身动作。
//   3. 关闭的模块保留同名任务（占位实现）：调用时打印"已禁用"并正常结束（退出码 0），
//      便于 CI 用同一串任务名跑不同配置，也避免"任务不存在"的半可用状态。
//   4. 配置值只接受 true / false；写错在配置阶段立即失败并给出可操作的中文提示。
// ===========================================================================

val buildModulesConfigFile = rootProject.file("config/build-modules.properties")
val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
val npmExecutable = if (isWindowsHost) "npm.cmd" else "npm"
val moduleGroup = "可选构建模块"

val buildModulesConfig: Map<String, String> = run {
    if (!buildModulesConfigFile.exists()) {
        emptyMap()
    } else {
        Properties().apply { buildModulesConfigFile.inputStream().use { load(it) } }
            .map { (key, value) -> key.toString() to value.toString() }
            .toMap()
    }
}

fun moduleEnabled(name: String, default: Boolean): Boolean {
    val key = "hpa.modules.$name.enabled"
    // 优先级：命令行 -P > 配置文件 > 内置默认值（CI 可临时覆盖，不必改文件）
    val raw = (findProperty(key) as? String) ?: buildModulesConfig[key] ?: default.toString()
    return when (raw.trim().lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> throw GradleException(
            "$key 取值非法：\"$raw\"。只接受 true / false（配置文件 $buildModulesConfigFile，" +
                "或命令行 -P$key=true|false）。",
        )
    }
}

fun moduleOption(name: String, option: String, default: String): String {
    val key = "hpa.modules.$name.$option"
    return (findProperty(key) as? String)?.takeIf { it.isNotBlank() } ?: buildModulesConfig[key] ?: default
}

/** 同步执行外部命令并合并 stderr，返回 退出码 to 输出；超时按失败处理并销毁进程。 */
fun shell(
    vararg command: String,
    workingDir: File? = null,
    timeoutSeconds: Long = 30,
): Pair<Int, String> {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    workingDir?.let { builder.directory(it) }
    val process = runCatching { builder.start() }
        .getOrElse { return -1 to (it.message ?: "无法启动命令：${command.joinToString(" ")}") }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    return if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.exitValue() to output
    } else {
        process.destroyForcibly()
        -1 to "$output\n[命令超时 ${timeoutSeconds}s]"
    }
}

fun shellOk(
    vararg command: String,
    workingDir: File? = null,
    timeoutSeconds: Long = 30,
): String? = shell(*command, workingDir = workingDir, timeoutSeconds = timeoutSeconds)
    .let { (code, output) -> output.takeIf { code == 0 } }

fun git(vararg args: String, timeoutSeconds: Long = 30): Pair<Int, String> =
    shell("git", *args, workingDir = rootProject.projectDir, timeoutSeconds = timeoutSeconds)

fun gitOk(vararg args: String, timeoutSeconds: Long = 30): String? =
    git(*args, timeoutSeconds = timeoutSeconds).let { (code, output) -> output.takeIf { code == 0 } }

/** Android SDK 路径：local.properties 的 sdk.dir > ANDROID_HOME > ANDROID_SDK_ROOT。 */
fun androidSdkDir(): File? {
    val fromLocal = runCatching {
        rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
    }.getOrNull()
    val raw = fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return raw?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
}

fun registerDisabledModuleStub(taskName: String, moduleName: String, capability: String) {
    tasks.register(taskName) {
        group = moduleGroup
        description = "【已禁用】$capability（开关 hpa.modules.$moduleName.enabled=false）"
        doLast {
            logger.lifecycle(
                "模块 $moduleName 已禁用：$capability 未执行。" +
                    "启用方式：把 $buildModulesConfigFile 的 hpa.modules.$moduleName.enabled 改为 true，" +
                    "或临时加 -Phpa.modules.$moduleName.enabled=true。",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 模块 1：Android 平台构建支持
//   androidCheck   —— JDK / SDK / Wrapper 前置校验（不改任何产物）
//   androidPackage —— 构建 Debug APK 并按时间戳归档（依赖 :app:assembleDebug）
// 关闭时这两个任务只打印提示；AGP 自身的 assembleDebug 始终可用。
// ---------------------------------------------------------------------------
if (moduleEnabled("android", true)) {
    tasks.register("androidCheck") {
        group = moduleGroup
        description = "校验 Android 平台构建前置条件（JDK 17+ / SDK 36 / Gradle Wrapper）"
        doLast {
            val problems = mutableListOf<String>()
            val javaVersion = System.getProperty("java.version").orEmpty()
            val javaMajor = Regex("""^(?:1\.)?(\d+)""").find(javaVersion)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            if (javaMajor < 17) problems += "JDK 版本过低（$javaVersion），AGP 8.x 要求 JDK 17+"
            val sdk = androidSdkDir()
            when {
                sdk == null ->
                    problems += "未找到 Android SDK：请在 local.properties 写 sdk.dir，或设置 ANDROID_HOME"
                !sdk.resolve("platforms/android-36").isDirectory ->
                    problems += "Android SDK 缺少 platforms/android-36（:app 的 compileSdk=36），请用 sdkmanager 安装"
            }
            if (!rootProject.file("gradlew.bat").exists() && !rootProject.file("gradlew").exists()) {
                problems += "缺少 Gradle Wrapper（gradlew / gradlew.bat）"
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Android 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew androidCheck。")
                    },
                )
            }
            logger.lifecycle("Android 模块校验通过：JDK $javaVersion；SDK ${sdk?.absolutePath}")
        }
    }

    tasks.register("androidPackage") {
        group = moduleGroup
        description = "构建 Debug APK 并按时间戳归档（归档目录见 hpa.modules.android.archiveDir）"
        dependsOn(":app:assembleDebug")
        doLast {
            val apk = project(":app").layout.buildDirectory
                .file("outputs/apk/debug/app-debug.apk").get().asFile
            if (!apk.exists()) throw GradleException("未找到构建产物：${apk.absolutePath}")
            // 归档是"复制"，不动 app/build/outputs 下的原始产物，避免破坏后续发布脚本
            val archiveDir = rootProject.file(moduleOption("android", "archiveDir", "build/apk-archive"))
            archiveDir.mkdirs()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val target = archiveDir.resolve("app-debug_$timestamp.apk")
            apk.copyTo(target, overwrite = true)
            logger.lifecycle(
                "APK 归档：${target.absolutePath}（${target.length() / 1024 / 1024} MB，versionCode=$buildNumber）",
            )
        }
    }
} else {
    registerDisabledModuleStub("androidCheck", "android", "Android 平台构建前置校验")
    registerDisabledModuleStub("androidPackage", "android", "Android 平台构建与归档")
}

// ---------------------------------------------------------------------------
// 模块 2：Vue 开发服务器集成（官网 website/）
//   vueCheck   —— Node 18+ / npm / package.json 脚本校验
//   vueInstall —— node_modules 缺失时安装依赖
//   vueDev     —— 长驻开发服务器（npm run dev:web），输出直通终端
//   vueBuild   —— 静态构建（npm run build → website/dist）
// 关闭时全部只打印提示，不触碰 website/。
// ---------------------------------------------------------------------------
val vueDir = rootProject.file(moduleOption("vue", "dir", "website"))

if (moduleEnabled("vue", false)) {
    tasks.register("vueCheck") {
        group = moduleGroup
        description = "校验 Vue 开发环境（Node 18+ / npm / website 依赖与脚本）"
        doLast {
            val problems = mutableListOf<String>()
            val nodeVersion = shellOk("node", "--version")
            val npmVersion = shellOk(npmExecutable, "--version")
            if (nodeVersion == null) {
                problems += "未找到 Node.js（website 要求 >=18），请安装后重试"
            } else {
                val major = nodeVersion.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
                if (major < 18) problems += "Node.js 版本过低（$nodeVersion），website 要求 >=18"
            }
            if (npmVersion == null) problems += "未找到 npm（$npmExecutable）"
            if (!vueDir.isDirectory) problems += "Vue 工程目录不存在：${vueDir.absolutePath}"
            val packageJson = vueDir.resolve("package.json")
            if (!packageJson.exists()) {
                problems += "缺少 ${packageJson.absolutePath}"
            } else {
                val text = packageJson.readText()
                val devScript = moduleOption("vue", "devScript", "dev:web")
                listOf(devScript, "build").forEach { script ->
                    if (!text.contains("\"$script\"")) problems += "package.json 缺少脚本 \"$script\""
                }
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Vue 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew vueCheck。")
                    },
                )
            }
            val installed = if (vueDir.resolve("node_modules").isDirectory) "已安装" else "未安装（先跑 vueInstall）"
            logger.lifecycle("Vue 模块校验通过：node $nodeVersion / npm $npmVersion；依赖$installed")
        }
    }

    tasks.register<Exec>("vueInstall") {
        group = moduleGroup
        description = "安装官网前端依赖（node_modules 已存在时跳过）"
        workingDir = vueDir
        commandLine(npmExecutable, "install")
        onlyIf {
            val installed = vueDir.resolve("node_modules").isDirectory
            if (installed) logger.lifecycle("website/node_modules 已存在，跳过 npm install")
            !installed
        }
    }

    tasks.register<Exec>("vueDev") {
        group = moduleGroup
        description = "启动 Vue 开发服务器（长驻任务，Ctrl+C 结束）"
        dependsOn("vueInstall")
        workingDir = vueDir
        commandLine(npmExecutable, "run", moduleOption("vue", "devScript", "dev:web"))
        // 长驻服务必须直通终端，否则用户在 Gradle 里看不到任何日志
        standardOutput = System.out
        errorOutput = System.err
    }

    tasks.register<Exec>("vueBuild") {
        group = moduleGroup
        description = "构建官网静态产物（npm run build → website/dist）"
        dependsOn("vueInstall")
        workingDir = vueDir
        commandLine(npmExecutable, "run", "build")
    }
} else {
    registerDisabledModuleStub("vueCheck", "vue", "Vue 开发环境校验")
    registerDisabledModuleStub("vueInstall", "vue", "Vue 前端依赖安装")
    registerDisabledModuleStub("vueDev", "vue", "Vue 开发服务器")
    registerDisabledModuleStub("vueBuild", "vue", "Vue 静态构建")
}

// ---------------------------------------------------------------------------
// 模块 3：Git 版本控制工具链集成
//   gitCheck  —— git 可用性与仓库有效性校验
//   gitStatus —— 分支、上游与工作区变更摘要（只读）
//   gitInfo   —— 分支 / 提交 / 脏标记 / 远程 / 版本号落盘为 JSON（只读）
// ---------------------------------------------------------------------------
if (moduleEnabled("git", false)) {
    tasks.register("gitCheck") {
        group = moduleGroup
        description = "校验 Git 工具链与仓库有效性"
        doLast {
            val problems = mutableListOf<String>()
            val version = shellOk("git", "--version") ?: run {
                problems += "未找到 git，请安装并加入 PATH"
                null
            }
            if (gitOk("rev-parse", "--is-inside-work-tree") != "true") {
                problems += "当前目录不是 Git 仓库：${rootProject.projectDir}"
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Git 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew gitCheck。")
                    },
                )
            }
            logger.lifecycle("Git 模块校验通过：$version")
        }
    }

    tasks.register("gitStatus") {
        group = moduleGroup
        description = "打印当前分支、上游跟踪与工作区变更摘要（只读，不改仓库）"
        doLast {
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD") ?: "未知"
            val upstream = gitOk("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
            logger.lifecycle("分支：$branch；上游：${upstream ?: "未设置"}")
            val changes = gitOk("status", "--short").orEmpty()
            if (changes.isBlank()) {
                logger.lifecycle("工作区干净，无未提交改动")
            } else {
                val lines = changes.lines()
                logger.lifecycle("未提交改动 ${lines.size} 项：")
                lines.take(50).forEach { logger.lifecycle("  $it") }
                if (lines.size > 50) logger.lifecycle("  … 其余 ${lines.size - 50} 项已省略")
            }
        }
    }

    tasks.register("gitInfo") {
        group = moduleGroup
        description = "把分支/提交/脏标记/远程/版本号写入 JSON（默认 build/build-info/git-info.json）"
        doLast {
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
            val commit = gitOk("rev-parse", "HEAD")
            val commitShort = gitOk("rev-parse", "--short", "HEAD")
            val commitCount = gitOk("rev-list", "--count", "HEAD")
            val dirty = !gitOk("status", "--porcelain").isNullOrBlank()
            val remotes = gitOk("remote", "-v").orEmpty()
                .lines()
                .filter { it.contains("(fetch)") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) "\"${jsonEscape(parts[0])}\": \"${jsonEscape(parts[1])}\"" else null
                }
            val outFile = rootProject.file(moduleOption("git", "output", "build/build-info/git-info.json"))
            outFile.parentFile?.mkdirs()
            outFile.writeText(
                buildString {
                    appendLine("{")
                    appendLine("  \"generatedAt\": \"${LocalDateTime.now()}\",")
                    appendLine("  \"buildNumber\": $buildNumber,")
                    appendLine("  \"branch\": \"${jsonEscape(branch ?: "")}\",")
                    appendLine("  \"commit\": \"${jsonEscape(commit ?: "")}\",")
                    appendLine("  \"commitShort\": \"${jsonEscape(commitShort ?: "")}\",")
                    appendLine("  \"commitCount\": ${commitCount?.toIntOrNull() ?: 0},")
                    appendLine("  \"dirty\": $dirty,")
                    appendLine("  \"remotes\": {${remotes.joinToString(", ")}}")
                    appendLine("}")
                },
            )
            logger.lifecycle("Git 构建信息已写入：${outFile.absolutePath}")
        }
    }
} else {
    registerDisabledModuleStub("gitCheck", "git", "Git 工具链校验")
    registerDisabledModuleStub("gitStatus", "git", "Git 工作区状态摘要")
    registerDisabledModuleStub("gitInfo", "git", "Git 构建信息落盘")
}

fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

// ---------------------------------------------------------------------------
// 模块 4：GitHub 仓库关联与同步
//   githubCheck —— 校验远程是否已关联（不联网，离线可用）
//   githubLink  —— 按 -PgithubUrl=<url> 添加或更新远程地址；不带参数时只打印现状
//   githubSync  —— 推送当前分支；默认"演练"，必须显式 -PconfirmSync=true 才真正推送
// 该模块永不自动推送，也不会改写远程配置（除非显式调用 githubLink + githubUrl）。
// ---------------------------------------------------------------------------
val githubRemoteName = moduleOption("github", "remote", "github")

if (moduleEnabled("github", false)) {
    tasks.register("githubCheck") {
        group = moduleGroup
        description = "校验 GitHub 远程关联与可选工具（gh CLI）"
        doLast {
            val problems = mutableListOf<String>()
            val url = gitOk("remote", "get-url", githubRemoteName)
            if (url == null) {
                problems += "未配置远程 \"$githubRemoteName\"。关联方式：" +
                    ".\\gradlew githubLink -PgithubUrl=https://github.com/<user>/<repo>.git"
            }
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
            if (branch == null) problems += "当前不在有效 Git 分支上"
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("GitHub 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew githubCheck。")
                    },
                )
            }
            val gh = shellOk("gh", "--version")?.lineSequence()?.firstOrNull()
            logger.lifecycle(
                "GitHub 模块校验通过：远程 $githubRemoteName → $url；分支 $branch；" +
                    "gh CLI ${if (gh != null) "可用（$gh）" else "未安装（可选，仅影响体验）"}",
            )
        }
    }

    tasks.register("githubLink") {
        group = moduleGroup
        description = "关联 GitHub 远程：-PgithubUrl=<url> 添加或更新；不带参数仅打印当前配置"
        doLast {
            val url = (findProperty("githubUrl") as? String)?.trim()
            val current = gitOk("remote", "get-url", githubRemoteName)
            if (url.isNullOrBlank()) {
                logger.lifecycle("当前远程 $githubRemoteName：${current ?: "未配置"}")
                logger.lifecycle(
                    "关联/更新方式：.\\gradlew githubLink -PgithubUrl=https://github.com/<user>/<repo>.git",
                )
                return@doLast
            }
            if (!url.startsWith("https://") && !url.startsWith("git@") && !url.startsWith("ssh://")) {
                throw GradleException("githubUrl 必须是 https:// 或 git@/ssh:// 形式的仓库地址，当前为：$url")
            }
            val (code, output) = if (current == null) {
                git("remote", "add", githubRemoteName, url)
            } else {
                git("remote", "set-url", githubRemoteName, url)
            }
            if (code != 0) throw GradleException("远程配置失败：$output")
            logger.lifecycle("远程 $githubRemoteName 已关联：$url")
        }
    }

    tasks.register("githubSync") {
        group = moduleGroup
        description = "推送当前分支到 GitHub 远程（需 -PconfirmSync=true；默认只做演练检查）"
        doLast {
            val url = gitOk("remote", "get-url", githubRemoteName)
                ?: throw GradleException(
                    "未配置远程 \"$githubRemoteName\"，先执行 githubLink -PgithubUrl=<url>。",
                )
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
                ?: throw GradleException("当前不在有效 Git 分支上，无法推送。")
            val confirm = (findProperty("confirmSync") as? String)?.equals("true", ignoreCase = true) == true
            logger.lifecycle("GitHub 远程：$url")
            logger.lifecycle("待推送分支：$branch")
            if (!confirm) {
                logger.lifecycle("演练模式：未执行推送。确认无误后加 -PconfirmSync=true 再跑一次。")
                return@doLast
            }
            val reachable = git("ls-remote", "--heads", githubRemoteName, branch, timeoutSeconds = 20)
            if (reachable.first != 0) {
                throw GradleException(
                    "无法访问 GitHub 远程（网络或凭据问题）：$url\n${reachable.second}\n" +
                        "可先用 githubCheck 校验关联，网络恢复后重试。",
                )
            }
            val (code, output) = git("push", githubRemoteName, branch, timeoutSeconds = 120)
            if (code != 0) throw GradleException("推送失败（$branch → $githubRemoteName）：\n$output")
            logger.lifecycle("已推送 $branch → $githubRemoteName")
        }
    }
} else {
    registerDisabledModuleStub("githubCheck", "github", "GitHub 远程关联校验")
    registerDisabledModuleStub("githubLink", "github", "GitHub 远程关联/更新")
    registerDisabledModuleStub("githubSync", "github", "GitHub 分支同步")
}

// ---------------------------------------------------------------------------
// 汇总入口：状态报告 + 兼容性校验
//   buildModules      —— 只读状态表（开关、前置条件、任务、启用方式），永不失败
//   buildModulesCheck —— 对"已启用"的模块逐个跑各自的 check（禁用的只打印提示）
// ---------------------------------------------------------------------------
tasks.register("buildModules") {
    group = moduleGroup
    description = "列出全部可选构建模块的开关状态、前置条件与任务（只读，不触发构建）"
    doLast {
        val enabled = mapOf(
            "android" to moduleEnabled("android", true),
            "vue" to moduleEnabled("vue", false),
            "git" to moduleEnabled("git", false),
            "github" to moduleEnabled("github", false),
        )
        val sdk = androidSdkDir()
        val node = shellOk("node", "--version")
        val npm = shellOk(npmExecutable, "--version")
        val gitVersion = shellOk("git", "--version")
        val gh = shellOk("gh", "--version")?.lineSequence()?.firstOrNull()
        val githubUrl = gitOk("remote", "get-url", githubRemoteName)
        val vueDeps = if (vueDir.resolve("node_modules").isDirectory) "已安装" else "未安装"

        fun state(on: Boolean) = if (on) "启用" else "禁用"

        logger.lifecycle(
            buildString {
                appendLine("可选构建模块状态（配置文件：${buildModulesConfigFile.name}）")
                appendLine("  [${state(enabled.getValue("android"))}] android  Android 平台构建")
                appendLine(
                    "        前置：JDK ${System.getProperty("java.version")}；" +
                        "SDK ${sdk?.absolutePath ?: "未找到"}",
                )
                appendLine("        任务：androidCheck、androidPackage")
                appendLine("  [${state(enabled.getValue("vue"))}] vue      Vue 开发服务器")
                appendLine("        前置：node ${node ?: "未找到"}；npm ${npm ?: "未找到"}；依赖$vueDeps")
                appendLine("        任务：vueCheck、vueInstall、vueDev、vueBuild")
                appendLine("  [${state(enabled.getValue("git"))}] git      Git 版本控制工具链")
                appendLine("        前置：${gitVersion ?: "未找到 git"}")
                appendLine("        任务：gitCheck、gitStatus、gitInfo")
                appendLine("  [${state(enabled.getValue("github"))}] github   GitHub 仓库关联与同步")
                appendLine(
                    "        前置：远程 $githubRemoteName → ${githubUrl ?: "未配置"}；" +
                        "gh ${gh ?: "未安装（可选）"}",
                )
                appendLine("        任务：githubCheck、githubLink、githubSync")
                appendLine(
                    "已启用 ${enabled.count { it.value }}/4。禁用模块的任务仍可调用，只打印提示。" +
                        "整体校验：.\\gradlew buildModulesCheck",
                )
            },
        )
        // 配置里出现未知模块键时给出告警（不失败，避免拼错一个键就卡住构建）
        val known = setOf("android", "vue", "git", "github")
        buildModulesConfig.keys
            .filter { it.startsWith("hpa.modules.") }
            .mapNotNull { it.split('.').getOrNull(2) }
            .filter { it !in known }
            .distinct()
            .forEach { logger.warn("警告：配置中存在未知模块键 hpa.modules.$it.*，已忽略") }
    }
}

tasks.register("buildModulesCheck") {
    group = moduleGroup
    description = "对已启用的可选模块执行兼容性校验（禁用模块自动跳过）"
    dependsOn("androidCheck", "vueCheck", "gitCheck", "githubCheck")
    doLast {
        val enabled = listOf(
            "android" to moduleEnabled("android", true),
            "vue" to moduleEnabled("vue", false),
            "git" to moduleEnabled("git", false),
            "github" to moduleEnabled("github", false),
        ).filter { it.second }.map { it.first }
        logger.lifecycle(
            if (enabled.isEmpty()) {
                "可选模块校验完成：四个模块全部禁用，未执行任何检查（默认构建流程不受影响）"
            } else {
                "可选模块校验完成：已启用 ${enabled.joinToString("、")}（${enabled.size}/4）"
            },
        )
    }
}
