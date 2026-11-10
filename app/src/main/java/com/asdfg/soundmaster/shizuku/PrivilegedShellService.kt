package com.asdfg.soundmaster.shizuku

import android.util.Log

/**
 * Runs inside Shizuku's shell/root process. This is intentionally tiny:
 * AudioPipe only needs a privileged shell for appops commands.
 */
class PrivilegedShellService : IPrivilegedShell.Stub() {

    override fun exec(command: String): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("Command failed ($exitCode): $output")
        }

        Log.d("PrivilegedShellService", "Executed: $command")
        return output
    }
}
