package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

data class RootProcessState(
    val isRunning: Boolean = false,
    val isForegroundService: Boolean = false,
    val isTop: Boolean = false,
    val pid: Int? = null,
    val activeComponents: Set<String> = emptySet()
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

            if (exitCode == 0) {
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

    suspend fun queryRootProcessStates(): Map<String, RootProcessState> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, RootProcessState>()
        try {
            // 1. ps -A -o PID,NAME
            val psRes = executeCommand("ps -A -o PID,NAME")
            if (psRes.isSuccess) {
                val output = psRes.getOrNull() ?: ""
                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("PID")) continue
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size >= 2) {
                        val pid = parts[0].toIntOrNull()
                        val name = parts[1]
                        val pkg = name.substringBefore(':')
                        map[pkg] = RootProcessState(
                            isRunning = true,
                            pid = pid
                        )
                    }
                }
            }

            // 2. dumpsys activity services (find active services and components causing wakeups)
            val svcRes = executeCommand("dumpsys activity services")
            if (svcRes.isSuccess) {
                val output = svcRes.getOrNull() ?: ""
                var currentPkg: String? = null
                var currentComp: String? = null
                for (line in output.lines()) {
                    if (line.contains("* ServiceRecord{")) {
                        val match = Regex("u0\\s+([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)").find(line)
                        currentPkg = match?.groupValues?.get(1)
                        val shortComp = match?.groupValues?.get(2)
                        currentComp = if (currentPkg != null && shortComp != null) {
                            if (shortComp.startsWith(".")) "$currentPkg$shortComp" else shortComp
                        } else null

                        if (currentPkg != null) {
                            val prev = map[currentPkg] ?: RootProcessState(isRunning = true)
                            val compSet = prev.activeComponents.toMutableSet()
                            if (currentComp != null) compSet.add(currentComp)
                            map[currentPkg] = prev.copy(isRunning = true, activeComponents = compSet)
                        }
                    }
                    if (currentPkg != null && (line.contains("isForeground=true") || line.contains("foregroundServiceType"))) {
                        val prev = map[currentPkg] ?: RootProcessState(isRunning = true)
                        map[currentPkg] = prev.copy(isForegroundService = true)
                    }
                }
            }

            // 3. dumpsys activity providers (find active providers causing wakeups e.g. TeraBoxProvider)
            val provRes = executeCommand("dumpsys activity providers")
            if (provRes.isSuccess) {
                val output = provRes.getOrNull() ?: ""
                for (line in output.lines()) {
                    if (line.contains("* ContentProviderRecord{")) {
                        val match = Regex("u0\\s+([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)").find(line)
                        val pkg = match?.groupValues?.get(1)
                        val shortComp = match?.groupValues?.get(2)
                        val compName = if (pkg != null && shortComp != null) {
                            if (shortComp.startsWith(".")) "$pkg$shortComp" else shortComp
                        } else null

                        if (pkg != null) {
                            val prev = map[pkg] ?: RootProcessState(isRunning = true)
                            val compSet = prev.activeComponents.toMutableSet()
                            if (compName != null) compSet.add(compName)
                            map[pkg] = prev.copy(isRunning = true, activeComponents = compSet)
                        }
                    }
                }
            }

            // 4. dumpsys activity processes (check TOP/FOREGROUND)
            val procRes = executeCommand("dumpsys activity processes")
            if (procRes.isSuccess) {
                val output = procRes.getOrNull() ?: ""
                for (line in output.lines()) {
                    if (line.contains("ProcessRecord{")) {
                        val match = Regex(":[0-9]+:([a-zA-Z0-9._]+)/").find(line)
                        val pkg = match?.groupValues?.get(1)?.substringBefore(':')
                        if (pkg != null && (line.contains("adj=0") || line.contains("TOP") || line.contains("FOREGROUND"))) {
                            val prev = map[pkg] ?: RootProcessState(isRunning = true)
                            map[pkg] = prev.copy(isTop = true)
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

    suspend fun restoreWakeUps(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cmds = listOf(
                "cmd appops set $packageName RUN_IN_BACKGROUND allow",
                "cmd appops set $packageName WAKE_LOCK allow",
                "cmd appops set $packageName START_FOREGROUND allow"
            )
            for (cmd in cmds) {
                executeCommand(cmd)
            }
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
                    // Disable specific component at system level using pm disable
                    executeCommand("pm disable ${path.componentName}")
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
                    executeCommand("pm enable ${path.componentName}")
                }
                com.example.model.WakeUpPathType.OP_WAKE_LOCK -> {
                    executeCommand("cmd appops set ${path.packageName} WAKE_LOCK allow")
                }
                com.example.model.WakeUpPathType.OP_RUN_IN_BACKGROUND -> {
                    executeCommand("cmd appops set ${path.packageName} RUN_IN_BACKGROUND allow")
                }
                com.example.model.WakeUpPathType.OP_SCHEDULED_ALARM -> {
                    executeCommand("cmd appops set ${path.packageName} SCHEDULE_EXACT_ALARM allow")
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
