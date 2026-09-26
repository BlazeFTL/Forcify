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
    val pid: Int? = null
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

            // 2. dumpsys activity services (to find active foreground services e.g. IDM+)
            val svcRes = executeCommand("dumpsys activity services")
            if (svcRes.isSuccess) {
                val output = svcRes.getOrNull() ?: ""
                var currentPkg: String? = null
                for (line in output.lines()) {
                    if (line.contains("* ServiceRecord{")) {
                        val match = Regex("u0\\s+([a-zA-Z0-9._]+)/").find(line)
                        currentPkg = match?.groupValues?.get(1)
                        if (currentPkg != null) {
                            val prev = map[currentPkg] ?: RootProcessState(isRunning = true)
                            map[currentPkg] = prev.copy(isRunning = true)
                        }
                    }
                    if (currentPkg != null && (line.contains("isForeground=true") || line.contains("foregroundServiceType"))) {
                        val prev = map[currentPkg] ?: RootProcessState(isRunning = true)
                        map[currentPkg] = prev.copy(isForegroundService = true)
                    }
                }
            }

            // 3. dumpsys activity processes (to check TOP/FOREGROUND)
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
}
