package com.example.engine

import kotlinx.coroutines.Dispatchers
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
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val errors = StringBuilder()
            while (errReader.readLine().also { line = it } != null) {
                errors.append(line).append("\n")
            }
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.isNotEmpty()) {
                Result.success(output.toString().trim())
            } else {
                Result.failure(Exception("Command failed ($exitCode): $errors"))
            }
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
            val cmd = if (targetPackages.isNotEmpty()) {
                val pkgList = targetPackages.joinToString(" ")
                "echo '===RECENTS==='; dumpsys activity recents | grep -E 'realActivity=|origActivity='; echo '===TOP==='; dumpsys activity top | grep -m 1 -E 'ACTIVITY |mResumedActivity|topResumedActivity'; dumpsys window | grep -m 1 'mCurrentFocus'; echo '===PIDS==='; for p in $pkgList; do pid=$(pidof \"\$p\" 2>/dev/null); if [ -n \"\$pid\" ]; then echo \"PID:\$p:\$pid\"; dumpsys activity services \"\$p\" | grep -m 4 -E 'ServiceRecord|isForeground=true'; fi; done; echo '===MEDIA==='; dumpsys media_session | grep -E 'package=|state=PlaybackState'; exit 0"
            } else {
                "echo '===RECENTS==='; dumpsys activity recents | grep -E 'realActivity=|origActivity='; echo '===TOP==='; dumpsys activity top | grep -m 1 -E 'ACTIVITY |mResumedActivity|topResumedActivity'; dumpsys window | grep -m 1 'mCurrentFocus'; echo '===PROCESSES==='; dumpsys activity processes | grep -E 'ProcessRecord\\{|curProcState=|procState='; echo '===SERVICES==='; dumpsys activity services | grep -E 'ServiceRecord|isForeground='; echo '===MEDIA==='; dumpsys media_session | grep -E 'package=|state=PlaybackState'; exit 0"
            }
            val res = executeCommand(cmd)
            val output = res.getOrNull() ?: ""
            if (output.isNotEmpty()) {
                var section = 1 // 1 = recents, 2 = top, 3 = processes/pids, 4 = services, 5 = media
                var lastServicePkg: String? = null
                var lastProcessPkg: String? = null
                var lastMediaPkg: String? = null

                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (trimmed == "===RECENTS===") {
                        section = 1
                        continue
                    }
                    if (trimmed == "===TOP===") {
                        section = 2
                        continue
                    }
                    if (trimmed == "===PROCESSES===" || trimmed == "===PIDS===") {
                        section = 3
                        continue
                    }
                    if (trimmed == "===SERVICES===") {
                        section = 4
                        continue
                    }
                    if (trimmed == "===MEDIA===") {
                        section = 5
                        continue
                    }

                    when (section) {
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
                            // dumpsys activity top: "ACTIVITY org.mozilla.firefox/org.mozilla.fenix.HomeActivity ..."
                            // or dumpsys window: "mCurrentFocus=Window{... u0 org.mozilla.firefox/...}"
                            val match = Regex("(?:ACTIVITY|u0)\\s+([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                        Regex("mCurrentFocus=.*\\s+([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                        Regex("([a-zA-Z0-9._]+)/[a-zA-Z0-9._]+").find(trimmed)
                            val topPkg = match?.groupValues?.get(1)
                            if (!topPkg.isNullOrEmpty() && !topPkg.contains("launcher") && !topPkg.contains("forcify")) {
                                val existing = map[topPkg] ?: RootProcessState(isRunning = true)
                                map[topPkg] = existing.copy(isRunning = true, isTop = true)
                            }
                        }
                        3 -> {
                            if (trimmed.startsWith("PID:")) {
                                val parts = trimmed.split(":")
                                if (parts.size >= 3) {
                                    val pkg = parts[1]
                                    val pid = parts[2].toIntOrNull()
                                    lastProcessPkg = pkg
                                    val existing = map[pkg] ?: RootProcessState()
                                    map[pkg] = existing.copy(isRunning = true, pid = pid)
                                }
                            } else if (trimmed.contains("ServiceRecord{") || trimmed.contains("isForeground=true")) {
                                val pkgMatch = Regex("u0\\s+([a-zA-Z0-9._]+)/").find(trimmed)
                                val pkg = pkgMatch?.groupValues?.get(1) ?: lastProcessPkg
                                if (pkg != null) {
                                    val existing = map[pkg] ?: RootProcessState(isRunning = true)
                                    val isFg = trimmed.contains("isForeground=true")
                                    map[pkg] = existing.copy(
                                        isRunning = true,
                                        hasActiveService = true,
                                        isForegroundService = isFg || existing.isForegroundService
                                    )
                                }
                            } else {
                                // Full dumpsys activity processes fallback
                                if (trimmed.contains("ProcessRecord{")) {
                                    val match = Regex("u0\\s+[a-zA-Z0-9._]+:([a-zA-Z0-9._]+)").find(trimmed) ?:
                                                Regex(":([a-zA-Z0-9._]+)/").find(trimmed) ?:
                                                Regex("u0\\s+([a-zA-Z0-9._]+)").find(trimmed)
                                    if (match != null) {
                                        val candidate = match.groupValues[1].substringBefore(':')
                                        if (candidate.contains('.')) {
                                            lastProcessPkg = candidate
                                        }
                                    }
                                }
                                if (lastProcessPkg != null) {
                                    if (trimmed.contains("procState=TOP") || trimmed.contains("curProcState=TOP")) {
                                        val existing = map[lastProcessPkg] ?: RootProcessState(isRunning = true)
                                        map[lastProcessPkg] = existing.copy(isRunning = true, isTop = true)
                                    } else if (trimmed.contains("procState=FGS") || trimmed.contains("curProcState=FGS") || trimmed.contains("procState=BFGS")) {
                                        val existing = map[lastProcessPkg] ?: RootProcessState(isRunning = true)
                                        map[lastProcessPkg] = existing.copy(isRunning = true, isForegroundService = true)
                                    } else if (trimmed.contains("procState=SV") || trimmed.contains("curProcState=SV") || trimmed.contains("procState=BGS") || trimmed.contains("procState=SERVICE")) {
                                        val existing = map[lastProcessPkg] ?: RootProcessState(isRunning = true)
                                        map[lastProcessPkg] = existing.copy(isRunning = true, hasActiveService = true)
                                    } else if (trimmed.contains("procState=CAC") || trimmed.contains("curProcState=CAC") || trimmed.contains("procState=CEM")) {
                                        val existing = map[lastProcessPkg] ?: RootProcessState()
                                        map[lastProcessPkg] = existing.copy(isCached = true)
                                    }
                                }
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
                        5 -> {
                            if (trimmed.contains("package=")) {
                                val match = Regex("package=([a-zA-Z0-9._]+)").find(trimmed)
                                if (match != null) {
                                    lastMediaPkg = match.groupValues[1]
                                }
                            }
                            if (trimmed.contains("state=3") && lastMediaPkg != null) {
                                val existing = map[lastMediaPkg] ?: RootProcessState(isRunning = true)
                                map[lastMediaPkg] = existing.copy(isRunning = true, isForegroundService = true)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Root commands might fail if su is denied
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
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.write(script.toByteArray(Charsets.UTF_8))
            os.writeBytes("\nexit\n")
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
