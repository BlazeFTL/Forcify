package com.example.engine

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

data class RootProcessState(
    val isRunning: Boolean = false,
    val hasActiveService: Boolean = false,
    val isForegroundService: Boolean = false,
    val isTop: Boolean = false,
    val isCached: Boolean = false,
    val pid: Int? = null,
    val activeComponents: Set<String> = emptySet(),
    val isInRecents: Boolean = false
)

object RootExecutor {

    private var persistentProcess: Process? = null
    private var persistentOutputStream: DataOutputStream? = null
    private var persistentReader: BufferedReader? = null
    private val shellMutex = Mutex()

    @Synchronized
    private fun initOrGetPersistentShell(): Boolean {
        if (persistentProcess != null) {
            try {
                persistentProcess!!.exitValue()
                // If it didn't throw, the process has exited
                destroyPersistentShell()
            } catch (e: IllegalThreadStateException) {
                // Process is still alive and ready!
                return true
            }
        }
        return try {
            val process = Runtime.getRuntime().exec("su")
            persistentProcess = process
            persistentOutputStream = DataOutputStream(process.outputStream)
            persistentReader = BufferedReader(InputStreamReader(process.inputStream))
            true
        } catch (e: Exception) {
            destroyPersistentShell()
            false
        }
    }

    @Synchronized
    private fun destroyPersistentShell() {
        try {
            persistentOutputStream?.writeBytes("exit\n")
            persistentOutputStream?.flush()
        } catch (e: Exception) { /* ignore */ }
        try { persistentOutputStream?.close() } catch (e: Exception) { }
        try { persistentReader?.close() } catch (e: Exception) { }
        try { persistentProcess?.destroy() } catch (e: Exception) { }
        persistentProcess = null
        persistentOutputStream = null
        persistentReader = null
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) return@withContext true
        }

        // Try executing su command directly
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            !line.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        shellMutex.withLock {
            if (initOrGetPersistentShell()) {
                val token = System.nanoTime().toString()
                try {
                    val os = persistentOutputStream ?: return@withLock false
                    val reader = persistentReader ?: return@withLock false
                    os.writeBytes("id\necho __FORCIFY_ROOT_${token}__ $?\n")
                    os.flush()
                    var line: String?
                    var hasRoot = false
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: break
                        if (l.contains("uid=0")) hasRoot = true
                        if (l.startsWith("__FORCIFY_ROOT_${token}__")) break
                    }
                    hasRoot
                } catch (e: Exception) {
                    destroyPersistentShell()
                    false
                }
            } else {
                false
            }
        }
    }

    /**
     * Executes root command through single persistent root shell:
     * - Spawns su ONCE (no repeated "Forcify was granted root permission" toasts!)
     * - Instant 2-5ms execution (no process fork/exec overhead)
     * - Thread-safe with Mutex serialization
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        shellMutex.withLock {
            try {
                if (initOrGetPersistentShell()) {
                    val os = persistentOutputStream ?: throw Exception("Root shell output stream null")
                    val reader = persistentReader ?: throw Exception("Root shell reader null")
                    val token = System.nanoTime().toString()
                    val endMarker = "__FORCIFY_EOF_${token}__"

                    // Execute command and output the end marker with exit code
                    os.writeBytes("$command\necho $endMarker $?\n")
                    os.flush()

                    val output = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: break
                        if (l.startsWith(endMarker)) {
                            break
                        }
                        output.append(l).append("\n")
                    }

                    return@withLock Result.success(output.toString().trim())
                }
            } catch (e: Exception) {
                destroyPersistentShell()
            }

            // Fallback to one-shot execution only if persistent shell encounters an issue
            executeCommandOneShot(command)
        }
    }

    private fun executeCommandOneShot(command: String): Result<String> {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\nexit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            Result.success(output.toString().trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val SAFE_PKG = Regex("^[A-Za-z0-9._]+$")

    suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SAFE_PKG.matches(packageName)) return@withContext Result.failure(IllegalArgumentException("Bad package"))
        val result = executeCommand("cmd activity force-stop $packageName >/dev/null 2>&1 || am force-stop $packageName")
        invalidateCache()
        if (result.isSuccess) Result.success(Unit) else Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
    }

    /**
     * Force stops many packages with one shell round trip per chunk.
     * Uses 'cmd activity' (binder call, no JVM start) with 'am' fallback, chunk members run in parallel.
     */
    suspend fun forceStopPackages(
        packages: List<String>,
        chunkSize: Int = 8,
        onChunkDone: (doneCount: Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val safe = packages.filter { SAFE_PKG.matches(it) }.distinct()
        var done = 0
        var failure: Throwable? = null
        for (chunk in safe.chunked(chunkSize.coerceAtLeast(1))) {
            val list = chunk.joinToString(" ")
            val cmd = "for p in $list; do ( cmd activity force-stop \"\$p\" >/dev/null 2>&1 || am force-stop \"\$p\" >/dev/null 2>&1 ) & done; wait"
            val res = executeCommand(cmd)
            if (res.isFailure) failure = res.exceptionOrNull()
            done += chunk.size
            onChunkDone(done)
        }
        invalidateCache()
        val f = failure
        if (f == null) Result.success(Unit) else Result.failure(f)
    }

    private data class FastPart(
        val pids: Map<String, Int> = emptyMap(),
        val top: Set<String> = emptySet(),
        val ok: Boolean = false,
        val at: Long = 0L
    )

    private data class DeepPart(
        val recents: Set<String> = emptySet(),
        val services: Set<String> = emptySet(),
        val foreground: Set<String> = emptySet(),
        val at: Long = 0L
    )

    private val queryMutex = Mutex()
    @Volatile private var fastPart = FastPart()
    @Volatile private var deepPart = DeepPart()

    @Volatile var lastQueryOk: Boolean = false
        private set

    @Volatile var lastKnownTop: Set<String> = emptySet()
        private set

    @Volatile var lastKnownTopAt: Long = 0L
        private set

    fun invalidateCache() {
        fastPart = fastPart.copy(at = 0L)
        deepPart = deepPart.copy(at = 0L)
    }

    private val PKG_REGEX = Regex("""u\d+\s+([A-Za-z0-9._]+)/""")
    private val RECENT_A_REGEX = Regex("""\bA=(?:\d+:)?([A-Za-z0-9._]+)""")
    private val RECENT_REAL_REGEX = Regex("""(?:realActivity|origActivity)=([A-Za-z0-9._]+)/""")
    private val SERVICE_REGEX = Regex("""ServiceRecord\{\S+\s+u\d+\s+([A-Za-z0-9._]+)/""")
    private val WS = Regex("""\s+""")

    private fun ignoredPkg(pkg: String): Boolean =
        pkg.isEmpty() || !pkg.contains('.') || pkg.contains("launcher")

    private suspend fun refreshFast() {
        val cmd = "echo '===PS==='; ps -A -o PID,NAME 2>/dev/null || ps -A 2>/dev/null || ps; " +
            "echo '===TOP==='; dumpsys activity activities 2>/dev/null | grep -E '^ *(topResumedActivity=|mResumedActivity:|ResumedActivity:)'; " +
            "echo '===END==='"
        val output = executeCommand(cmd).getOrNull().orEmpty()
        val pids = HashMap<String, Int>()
        val top = HashSet<String>()
        var section = 0
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            when (t) {
                "===PS===" -> { section = 1; continue }
                "===TOP===" -> { section = 2; continue }
                "===END===" -> { section = 0; continue }
            }
            when (section) {
                1 -> {
                    val parts = t.split(WS)
                    if (parts.size >= 2) {
                        val pid = parts[0].toIntOrNull() ?: parts[1].toIntOrNull()
                        val pkg = parts.last().substringBefore(':')
                        if (pid != null && !pkg.startsWith("/") && !ignoredPkg(pkg)) {
                            if (!pids.containsKey(pkg)) pids[pkg] = pid
                        }
                    }
                }
                2 -> {
                    val pkg = PKG_REGEX.find(t)?.groupValues?.get(1)
                    if (pkg != null && !ignoredPkg(pkg)) top.add(pkg)
                }
            }
        }
        val ok = pids.isNotEmpty()
        fastPart = FastPart(pids, top, ok, SystemClock.elapsedRealtime())
        lastQueryOk = ok
        if (top.isNotEmpty()) {
            lastKnownTop = top
            lastKnownTopAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun refreshDeep() {
        val cmd = "echo '===RECENTS==='; dumpsys activity recents 2>/dev/null | grep -E 'Recent #|realActivity=|origActivity='; " +
            "echo '===SERVICES==='; dumpsys activity services 2>/dev/null | grep -E 'ServiceRecord\\{|isForeground=true'; " +
            "echo '===END==='"
        val output = executeCommand(cmd).getOrNull().orEmpty()
        val recents = HashSet<String>()
        val services = HashSet<String>()
        val foreground = HashSet<String>()
        var section = 0
        var lastSvc: String? = null
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            when (t) {
                "===RECENTS===" -> { section = 1; continue }
                "===SERVICES===" -> { section = 2; lastSvc = null; continue }
                "===END===" -> { section = 0; continue }
            }
            when (section) {
                1 -> {
                    if (t.contains("Recent #")) {
                        RECENT_A_REGEX.find(t)?.groupValues?.get(1)?.let { if (!ignoredPkg(it)) recents.add(it) }
                    }
                    RECENT_REAL_REGEX.find(t)?.groupValues?.get(1)?.let { if (!ignoredPkg(it)) recents.add(it) }
                }
                2 -> {
                    val m = SERVICE_REGEX.find(t)
                    if (m != null) {
                        lastSvc = m.groupValues[1]
                        services.add(lastSvc)
                    }
                    if (t.contains("isForeground=true")) {
                        lastSvc?.let { foreground.add(it) }
                    }
                }
            }
        }
        deepPart = DeepPart(recents, services, foreground, SystemClock.elapsedRealtime())
    }

    /**
     * Process state snapshot through the persistent root shell.
     * Cheap part (ps + resumed activity) is cached [maxFastAgeMs], expensive part
     * (recents + services, filtered on device with grep and never truncated) is cached [maxDeepAgeMs].
     * Concurrent callers (daemon + UI) share results.
     */
    suspend fun queryRootProcessStates(
        targetPackages: Set<String> = emptySet(),
        maxDeepAgeMs: Long = 5_000L,
        maxFastAgeMs: Long = 600L
    ): Map<String, RootProcessState> = withContext(Dispatchers.IO) {
        try {
            queryMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                if (now - fastPart.at > maxFastAgeMs) refreshFast()
                if (now - deepPart.at > maxDeepAgeMs) refreshDeep()
            }
        } catch (e: Exception) {
            // su denied or shell died
        }
        val fast = fastPart
        val deep = deepPart
        if (!fast.ok) return@withContext emptyMap()

        val keys = HashSet<String>()
        keys.addAll(fast.pids.keys)
        keys.addAll(fast.top)
        keys.addAll(deep.recents)
        keys.addAll(deep.services)
        keys.addAll(deep.foreground)

        val map = HashMap<String, RootProcessState>()
        for (pkg in keys) {
            if (targetPackages.isNotEmpty() && !targetPackages.contains(pkg)) continue
            val pid = fast.pids[pkg]
            val isTop = fast.top.contains(pkg)
            val hasService = deep.services.contains(pkg)
            val isFgs = deep.foreground.contains(pkg)
            map[pkg] = RootProcessState(
                isRunning = pid != null || isTop || isFgs,
                hasActiveService = hasService,
                isForegroundService = isFgs,
                isTop = isTop,
                pid = pid,
                isInRecents = deep.recents.contains(pkg)
            )
        }
        map
    }

    suspend fun cutWakeUps(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Cut AppOps for background execution and wake locks
            val cmds = listOf(
                "cmd appops set $packageName RUN_IN_BACKGROUND ignore",
                "cmd appops set $packageName WAKE_LOCK ignore",
                "cmd appops set $packageName START_FOREGROUND ignore",
                "am force-stop $packageName"
            )
            for (cmd in cmds) {
                executeCommand(cmd)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeScript(script: String): Result<String> = withContext(Dispatchers.IO) {
        val sanitized = script.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("; ")
        if (sanitized.isBlank()) return@withContext Result.success("")
        executeCommand(sanitized)
    }

    suspend fun restoreWakeUps(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Restore to standard Android system default (NOT allow, which forces apps to run unconstrained)
            val script = """
                cmd appops set $packageName RUN_IN_BACKGROUND default
                cmd appops set $packageName RUN_ANY_IN_BACKGROUND default
                cmd appops set $packageName WAKE_LOCK default
                cmd appops set $packageName START_FOREGROUND default
                cmd appops set $packageName SCHEDULE_EXACT_ALARM default
                cmd appops set $packageName BOOT_COMPLETED default
            """.trimIndent()
            executeScript(script)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreWakeUpsBatch(packages: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (packages.isEmpty()) return@withContext Result.success(Unit)
            val sb = StringBuilder()
            for (pkg in packages) {
                sb.append("cmd appops set $pkg RUN_IN_BACKGROUND default\n")
                sb.append("cmd appops set $pkg RUN_ANY_IN_BACKGROUND default\n")
                sb.append("cmd appops set $pkg WAKE_LOCK default\n")
                sb.append("cmd appops set $pkg START_FOREGROUND default\n")
                sb.append("cmd appops set $pkg SCHEDULE_EXACT_ALARM default\n")
                sb.append("cmd appops set $pkg BOOT_COMPLETED default\n")
            }
            executeScript(sb.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cutSpecificWakeUpPath(path: com.example.model.WakeUpPath): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (path.type) {
                com.example.model.WakeUpPathType.PROVIDER_DOCUMENTS,
                com.example.model.WakeUpPathType.PROVIDER_CONTENT,
                com.example.model.WakeUpPathType.SERVICE_SYNC_ADAPTER,
                com.example.model.WakeUpPathType.RECEIVER_BOOT,
                com.example.model.WakeUpPathType.RECEIVER_CONNECTIVITY,
                com.example.model.WakeUpPathType.RECEIVER_POWER,
                com.example.model.WakeUpPathType.RECEIVER_USER_PRESENT,
                com.example.model.WakeUpPathType.RECEIVER_TRACKER,
                com.example.model.WakeUpPathType.RECEIVER_PUSH,
                com.example.model.WakeUpPathType.RECEIVER_PACKAGE,
                com.example.model.WakeUpPathType.RECEIVER_CUSTOM,
                com.example.model.WakeUpPathType.SERVICE_BACKGROUND,
                com.example.model.WakeUpPathType.SERVICE_FOREGROUND,
                com.example.model.WakeUpPathType.SERVICE_JOB -> {
                    // Disable specific component at system level using pm disable (requires pkg/component)
                    val comp = if (path.componentName.contains("/")) path.componentName else "${path.packageName}/${path.componentName}"
                    executeCommand("pm disable $comp")
                }
                com.example.model.WakeUpPathType.OP_WAKE_LOCK -> {
                    executeCommand("cmd appops set ${path.packageName} WAKE_LOCK ignore")
                }
                com.example.model.WakeUpPathType.OP_RUN_IN_BACKGROUND -> {
                    executeCommand("cmd appops set ${path.packageName} RUN_IN_BACKGROUND ignore")
                }
                com.example.model.WakeUpPathType.OP_SCHEDULED_ALARM -> {
                    executeCommand("cmd appops set ${path.packageName} SCHEDULE_EXACT_ALARM ignore")
                }
                com.example.model.WakeUpPathType.BATTERY_OPTIMIZATION -> {
                    executeCommand("dumpsys deviceidle whitelist -${path.packageName}")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreSpecificWakeUpPath(path: com.example.model.WakeUpPath): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (path.type) {
                com.example.model.WakeUpPathType.PROVIDER_DOCUMENTS,
                com.example.model.WakeUpPathType.PROVIDER_CONTENT,
                com.example.model.WakeUpPathType.SERVICE_SYNC_ADAPTER,
                com.example.model.WakeUpPathType.RECEIVER_BOOT,
                com.example.model.WakeUpPathType.RECEIVER_CONNECTIVITY,
                com.example.model.WakeUpPathType.RECEIVER_POWER,
                com.example.model.WakeUpPathType.RECEIVER_USER_PRESENT,
                com.example.model.WakeUpPathType.RECEIVER_TRACKER,
                com.example.model.WakeUpPathType.RECEIVER_PUSH,
                com.example.model.WakeUpPathType.RECEIVER_PACKAGE,
                com.example.model.WakeUpPathType.RECEIVER_CUSTOM,
                com.example.model.WakeUpPathType.SERVICE_BACKGROUND,
                com.example.model.WakeUpPathType.SERVICE_FOREGROUND,
                com.example.model.WakeUpPathType.SERVICE_JOB -> {
                    val comp = if (path.componentName.contains("/")) path.componentName else "${path.packageName}/${path.componentName}"
                    executeCommand("pm enable $comp")
                }
                com.example.model.WakeUpPathType.OP_WAKE_LOCK -> {
                    executeCommand("cmd appops set ${path.packageName} WAKE_LOCK default")
                }
                com.example.model.WakeUpPathType.OP_RUN_IN_BACKGROUND -> {
                    executeCommand("cmd appops set ${path.packageName} RUN_IN_BACKGROUND default; cmd appops set ${path.packageName} RUN_ANY_IN_BACKGROUND default; cmd appops set ${path.packageName} START_FOREGROUND default")
                }
                com.example.model.WakeUpPathType.OP_SCHEDULED_ALARM -> {
                    executeCommand("cmd appops set ${path.packageName} SCHEDULE_EXACT_ALARM default")
                }
                com.example.model.WakeUpPathType.BATTERY_OPTIMIZATION -> {
                    executeCommand("dumpsys deviceidle whitelist +${path.packageName}")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
