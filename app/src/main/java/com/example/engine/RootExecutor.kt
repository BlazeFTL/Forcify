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
        // Run am force-stop and backup pkill
        val result = executeCommand("am force-stop $packageName; pkill -f $packageName")
        if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }
    }

    suspend fun queryRootProcessStates(targetPackages: Set<String> = emptySet()): Map<String, RootProcessState> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, RootProcessState>()
        try {
            // Ultra-fast sub-40ms unified query executed through persistent root shell:
            // 1. ps runs first (< 10ms) giving all running PIDs and process names instantly
            // 2. dumpsys activity top & window currentFocus with immediate exit
            // 3. dumpsys activity recents limited to head
            // 4. dumpsys activity services limited to head
            // CRITICAL: DO NOT add 'exit 0' at the end - persistent shell must stay open!
            val cmd = "echo '===PS==='; ps -A -o PID,NAME 2>/dev/null || ps -A 2>/dev/null || ps; echo '===TOP==='; dumpsys activity top 2>/dev/null | head -n 25 | grep -m 1 -E 'ACTIVITY |mResumedActivity|topResumedActivity'; dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus'; echo '===RECENTS==='; dumpsys activity recents 2>/dev/null | head -n 35 | grep -m 6 -E 'realActivity=|origActivity='; echo '===SERVICES==='; dumpsys activity services 2>/dev/null | head -n 40 | grep -m 10 -E 'ServiceRecord\\{|isForeground=true'"
            val res = executeCommand(cmd)
            val output = res.getOrNull() ?: ""
            if (output.isNotEmpty()) {
                var section = 6 // default to 6 = PS
                var lastServicePkg: String? = null
                var lastProcessPkg: String? = null

                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (trimmed == "===PS===") {
                        section = 6
                        continue
                    }
                    if (trimmed == "===TOP===") {
                        section = 2
                        continue
                    }
                    if (trimmed == "===RECENTS===") {
                        section = 1
                        continue
                    }
                    if (trimmed == "===SERVICES===") {
                        section = 4
                        continue
                    }

                    when (section) {
                        6 -> {
                            // ps line parsing: PID NAME
                            val parts = trimmed.split(Regex("""\s+"""))
                            if (parts.size >= 2) {
                                val pid = parts[0].toIntOrNull() ?: parts.getOrNull(1)?.toIntOrNull()
                                val procName = parts.last()
                                val pkg = procName.substringBefore(':')
                                if (pid != null && pkg.contains('.') && !pkg.startsWith("/") && !pkg.contains("launcher") && !pkg.contains("forcify")) {
                                    val existing = map[pkg] ?: RootProcessState(isRunning = true)
                                    map[pkg] = existing.copy(isRunning = true, pid = pid)
                                }
                            }
                        }
                        1 -> {
                            // dumpsys activity recents: Task{... realActivity=org.mozilla.firefox/.App ...}
                            val match = Regex("realActivity=([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                        Regex("origActivity=([a-zA-Z0-9._]+)/").find(trimmed)
                            val recentPkg = match?.groupValues?.get(1)
                            if (!recentPkg.isNullOrEmpty() && !recentPkg.contains("launcher") && !recentPkg.contains("forcify")) {
                                val existing = map[recentPkg] ?: RootProcessState(isRunning = true)
                                map[recentPkg] = existing.copy(isRunning = true, isInRecents = true)
                            }
                        }
                        2 -> {
                            // dumpsys activity top or window focus
                            val match = Regex("(?:ACTIVITY|u0)\\s+([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                        Regex("mCurrentFocus=.*\\s+([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                        Regex("([a-zA-Z0-9._]+)/[a-zA-Z0-9._]+").find(trimmed)
                            val topPkg = match?.groupValues?.get(1)
                            if (!topPkg.isNullOrEmpty() && !topPkg.contains("launcher") && !topPkg.contains("forcify")) {
                                val existing = map[topPkg] ?: RootProcessState(isRunning = true)
                                map[topPkg] = existing.copy(isRunning = true, isTop = true)
                            }
                        }
                        4 -> {
                            // dumpsys activity services: ServiceRecord{... u0 package/.Service ...}
                            if (trimmed.contains("ServiceRecord{") || trimmed.contains("u0 ")) {
                                val match = Regex("u0\\s+([a-zA-Z0-9._]+)/").find(trimmed)
                                if (match != null) {
                                    lastServicePkg = match.groupValues[1]
                                    val existing = map[lastServicePkg] ?: RootProcessState(isRunning = true)
                                    map[lastServicePkg] = existing.copy(isRunning = true, hasActiveService = true)
                                }
                            }
                            if (trimmed.contains("isForeground=true") && lastServicePkg != null) {
                                val existing = map[lastServicePkg] ?: RootProcessState(isRunning = true)
                                map[lastServicePkg] = existing.copy(isRunning = true, isForegroundService = true)
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
