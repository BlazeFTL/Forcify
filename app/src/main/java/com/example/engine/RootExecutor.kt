package com.example.engine

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
    val isPerceptible: Boolean = false,
    val isMediaPlaying: Boolean = false,
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

    suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = executeCommand("am force-stop $packageName")
        if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }
    }

    suspend fun forceStopAppsBatch(packages: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext Result.success(Unit)
        try {
            for (chunk in packages.chunked(25)) {
                val cmd = chunk.joinToString("; ") { "am force-stop $it" }
                executeCommand(cmd)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun queryRootProcessStates(targetPackages: Set<String> = emptySet()): Map<String, RootProcessState> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, RootProcessState>()
        try {
            // Fast unified root query (~50-70ms) without truncating dumpsys before grep:
            // 1. ps: exact live PIDs
            // 2. dumpsys activity lru: exact ActivityManager proc states (TOP, FGS, BFGS, BTOP, IMPF, SVC, CAC)
            // 3. dumpsys activity/window top: active focused/resumed activity
            // 4. dumpsys activity recents: active recent tasks (grep first, then head)
            // 5. dumpsys activity services: all ServiceRecords & isForeground=true
            // 6. dumpsys media_session: active audio/media playback
            val cmd = "echo '===PS==='; ps -A -o PID,NAME 2>/dev/null || ps -A 2>/dev/null || ps; " +
                "echo '===LRU==='; dumpsys activity lru 2>/dev/null; " +
                "echo '===TOP==='; dumpsys activity activities 2>/dev/null | grep -m 4 -E 'topResumedActivity=|mResumedActivity=|ResumedActivity:'; dumpsys window 2>/dev/null | grep -m 3 -E 'mCurrentFocus=|mFocusedApp='; " +
                "echo '===RECENTS==='; dumpsys activity recents 2>/dev/null | grep -E 'Recent #|realActivity=|origActivity=' | head -n 60; " +
                "echo '===SERVICES==='; dumpsys activity services 2>/dev/null | grep -E 'ServiceRecord\\{|isForeground=true|foregroundId='; " +
                "echo '===MEDIA==='; dumpsys media_session 2>/dev/null | grep -E 'package=|state=PlaybackState'"

            val res = executeCommand(cmd)
            val output = res.getOrNull() ?: ""
            if (output.isNotEmpty()) {
                var section = 6
                var lastServicePkg: String? = null
                var lastMediaPkg: String? = null
                var currentRecentRank = 0
                val lruProcCounts = mutableMapOf<String, Boolean>() // tracks if any non-cached proc seen

                val lruProcRegex = Regex("""(\d+):([a-zA-Z0-9._]+)(?::[a-zA-Z0-9._-]*)?/u?\d+""")
                val lruTopRegex = Regex("""\bTOP\b""")
                val lruFgsRegex = Regex("""\b(FGS|BFGS)\b""")
                val lruPerceptibleRegex = Regex("""\b(BTOP|IMPF|IMPB|PER)\b""")
                val lruSvcRegex = Regex("""\b(SVC|RSVC)\b""")
                val lruCachedRegex = Regex("""\b(CAC|CEM|CCH|TRNB|LAST|HOME)\b""")
                val fgIdRegex = Regex("""foregroundId=([1-9]\d*)""")

                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    when (trimmed) {
                        "===PS===" -> { section = 6; continue }
                        "===LRU===" -> { section = 5; continue }
                        "===TOP===" -> { section = 2; continue }
                        "===RECENTS===" -> { section = 1; continue }
                        "===SERVICES===" -> { section = 4; lastServicePkg = null; continue }
                        "===MEDIA===" -> { section = 7; lastMediaPkg = null; continue }
                    }

                    when (section) {
                        6 -> {
                            // ps line parsing: PID NAME (or USER PID ... NAME)
                            val parts = trimmed.split(Regex("""\s+"""))
                            if (parts.size >= 2) {
                                val pid = parts[0].toIntOrNull() ?: parts.getOrNull(1)?.toIntOrNull()
                                val procName = parts.last()
                                val pkg = procName.substringBefore(':')
                                if (pid != null && pkg.contains('.') && !pkg.startsWith("/") && pkg != "com.example") {
                                    val existing = map[pkg] ?: RootProcessState(isRunning = true)
                                    map[pkg] = existing.copy(isRunning = true, pid = existing.pid ?: pid)
                                }
                            }
                        }
                        5 -> {
                            // dumpsys activity lru: # 12: fg TOP --- 2345:com.pkg/u0a123
                            val match = lruProcRegex.find(trimmed) ?: continue
                            val pid = match.groupValues[1].toIntOrNull()
                            val pkg = match.groupValues[2]
                            if (pkg == "com.example" || pkg == "system") continue

                            val prefix = trimmed.substringBefore(match.value).uppercase()
                            val isTop = lruTopRegex.containsMatchIn(prefix)
                            val isFgs = lruFgsRegex.containsMatchIn(prefix)
                            val isPerceptible = lruPerceptibleRegex.containsMatchIn(prefix)
                            val isSvc = lruSvcRegex.containsMatchIn(prefix)
                            val isCachedLine = !isTop && !isFgs && !isPerceptible && !isSvc && lruCachedRegex.containsMatchIn(prefix)

                            val existing = map[pkg] ?: RootProcessState(isRunning = true, pid = pid)
                            val hadActiveNonCached = lruProcCounts[pkg] == true || isTop || isFgs || isPerceptible || isSvc
                            if (hadActiveNonCached) {
                                lruProcCounts[pkg] = true
                            }
                            map[pkg] = existing.copy(
                                isRunning = true,
                                pid = existing.pid ?: pid,
                                isTop = existing.isTop || isTop,
                                isForegroundService = existing.isForegroundService || isFgs,
                                isPerceptible = existing.isPerceptible || isPerceptible,
                                hasActiveService = existing.hasActiveService || isSvc || isFgs,
                                isCached = if (hadActiveNonCached) false else isCachedLine
                            )
                        }
                        2 -> {
                            // Ignore systemui/keyguard/shade overlays
                            if (trimmed.contains("NotificationShade") || trimmed.contains("StatusBar") || trimmed.contains("Bouncer")) continue
                            val match = Regex("""(?:topResumedActivity=|mResumedActivity=|ResumedActivity:|mCurrentFocus=|mFocusedApp=).*?(?:u\d+\s+)?([a-zA-Z0-9._]+)/""").find(trimmed)
                                ?: Regex("""([a-zA-Z0-9._]+)/[a-zA-Z0-9._$]+""").find(trimmed)
                            val topPkg = match?.groupValues?.get(1)
                            if (!topPkg.isNullOrEmpty() && topPkg != "com.example" && topPkg != "com.android.systemui") {
                                val existing = map[topPkg]
                                if (existing != null && existing.isRunning) {
                                    map[topPkg] = existing.copy(isTop = true, isCached = false)
                                }
                            }
                        }
                        1 -> {
                            // dumpsys activity recents
                            val rankMatch = Regex("""Recent #(\d+)""").find(trimmed)
                            if (rankMatch != null) {
                                currentRecentRank = rankMatch.groupValues[1].toIntOrNull() ?: currentRecentRank
                                continue
                            }
                            val match = Regex("""realActivity=([a-zA-Z0-9._]+)/""").find(trimmed)
                                ?: Regex("""origActivity=([a-zA-Z0-9._]+)/""").find(trimmed)
                            val recentPkg = match?.groupValues?.get(1)
                            // CRITICAL: Only mark isInRecents if the process is ACTUALLY running in ps/lru!
                            // Force-stopped apps leave dead Task stubs in recents and must NOT be marked running!
                            if (!recentPkg.isNullOrEmpty() && recentPkg != "com.example" && recentPkg != "com.android.systemui") {
                                val existing = map[recentPkg]
                                if (existing != null && existing.isRunning && (currentRecentRank <= 10 || !existing.isCached)) {
                                    map[recentPkg] = existing.copy(isInRecents = true)
                                }
                            }
                        }
                        4 -> {
                            // dumpsys activity services
                            if (trimmed.startsWith("* ServiceRecord{") || trimmed.startsWith("ServiceRecord{")) {
                                val match = Regex("""u\d+\s+([a-zA-Z0-9._]+)/([a-zA-Z0-9._$]+)""").find(trimmed)
                                if (match != null) {
                                    val svcPkg = match.groupValues[1]
                                    val svcComp = match.groupValues[2]
                                    val existing = map[svcPkg]
                                    if (existing != null && existing.isRunning) {
                                        lastServicePkg = svcPkg
                                        map[svcPkg] = existing.copy(
                                            hasActiveService = true,
                                            isCached = false,
                                            activeComponents = existing.activeComponents + svcComp
                                        )
                                    } else {
                                        lastServicePkg = null
                                    }
                                } else {
                                    lastServicePkg = null
                                }
                            } else if (lastServicePkg != null && (trimmed.contains("isForeground=true") || fgIdRegex.containsMatchIn(trimmed))) {
                                val existing = map[lastServicePkg]
                                if (existing != null && existing.isRunning) {
                                    map[lastServicePkg!!] = existing.copy(
                                        isForegroundService = true,
                                        hasActiveService = true,
                                        isCached = false
                                    )
                                }
                            }
                        }
                        7 -> {
                            // dumpsys media_session
                            val pkgMatch = Regex("""package=([a-zA-Z0-9._]+)""").find(trimmed)
                            if (pkgMatch != null) {
                                lastMediaPkg = pkgMatch.groupValues[1]
                            } else if (lastMediaPkg != null && trimmed.contains("state=3")) {
                                val existing = map[lastMediaPkg]
                                if (existing != null && existing.isRunning) {
                                    map[lastMediaPkg!!] = existing.copy(
                                        isMediaPlaying = true,
                                        isCached = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Root commands might fail if su is denied
        }
        if (targetPackages.isEmpty()) map else map.filterKeys { targetPackages.contains(it) }
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
