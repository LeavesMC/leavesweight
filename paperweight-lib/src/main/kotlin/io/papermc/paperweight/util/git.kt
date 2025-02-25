/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.util

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.constants.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

class Git(private val repo: Path, private val env: Map<String, String> = emptyMap()) {

    @Suppress("unused")
    constructor(repo: Any) : this(repo.convertToPath())

    init {
        if (!repo.exists()) {
            throw PaperweightException("Git directory does not exist: $repo")
        }
    }

    fun withEnv(env: Map<String, String>): Git = Git(repo, env)

    private fun cmd(args: Array<out String>) =
        arrayOf("git", "-c", "commit.gpgsign=false", "-c", "core.safecrlf=false", *args)

    fun exec(providers: ProviderFactory, vararg args: String): Provider<String> {
        val cmd = cmd(args)
        val exec = providers.exec {
            workingDir = repo.toFile()
            commandLine = cmd.toMutableList()
            environment.putAll(env)
        }
        return exec.standardOutput.asText.zip(exec.result) { output, result ->
            if (result.exitValue != 0) {
                throw PaperweightException("Failed to execute command: '${cmd.joinToString(separator = " ")}'; Exit code ${result.exitValue}")
            }
            return@zip output
        }
    }

    fun disableAutoGpgSigningInRepo() {
        invoke("config", "commit.gpgSign", "false").executeSilently(silenceErr = true)
        invoke("config", "tag.gpgSign", "false").executeSilently(silenceErr = true)
    }

    operator fun invoke(vararg args: String): Command {
        val cmd = cmd(args)
        return try {
            val builder = ProcessBuilder(*cmd).directory(repo)
            builder.environment().putAll(env)

            val commandText = builder.command().joinToString(" ") { arg ->
                if (arg.codePoints().anyMatch { Character.isWhitespace(it) }) {
                    "'$arg'"
                } else {
                    arg
                }
            }
            Command(builder, commandText)
        } catch (e: IOException) {
            throw PaperweightException("Failed to execute command: ${cmd.joinToString(separator = " ")}", e)
        }
    }

    companion object {
        private const val IGNORE_GITIGNORE_PROPERTY_NAME = "paperweight.ignore-gitignore"

        var ignorePropertyField: Provider<Boolean>? = null
        fun ignoreProperty(providers: ProviderFactory): Provider<Boolean> {
            var current = ignorePropertyField
            if (current != null) {
                return current
            }
            current = providers.gradleProperty(IGNORE_GITIGNORE_PROPERTY_NAME).map { it.toBoolean() }.orElse(false)
            ignorePropertyField = current
            return current
        }

        fun add(ignoreGitIgnore: Provider<Boolean>, vararg args: String): Array<String> {
            return add(ignoreGitIgnore.get(), *args)
        }

        fun add(ignoreGitIgnore: Boolean, vararg args: String): Array<String> {
            return if (ignoreGitIgnore) {
                arrayOf("add", "--force", *args)
            } else {
                arrayOf("add", *args)
            }
        }

        fun checkForGit(providers: ProviderFactory) {
            val result = providers.exec {
                commandLine("git", "--version")
            }.result.get()
            if (result.exitValue != 0) {
                missingGit()
            }
        }

        fun checkForGit() {
            try {
                val proc = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
                proc.inputStream.copyTo(UselessOutputStream)
                if (proc.waitFor() == 0) {
                    return
                }
            } catch (_: Exception) {
            }
            missingGit()
        }

        private fun missingGit(): Nothing {
            throw PaperweightException("You must have git installed and available on your PATH in order to use paperweight.")
        }

        fun checkForGitRepo(directory: Path): Boolean {
            try {
                val proc = ProcessBuilder("git", "status").redirectErrorStream(true).directory(directory).start()
                proc.inputStream.copyTo(UselessOutputStream)
                if (proc.waitFor() == 0) {
                    return true
                }
            } catch (_: Exception) {}
            return false
        }
    }
}

class Command(private val processBuilder: ProcessBuilder, private val command: String) {

    private var outStream: OutputStream = UselessOutputStream
    private var errStream: OutputStream = UselessOutputStream

    fun run(): Int {
        if (paperweightDebug()) {
            // Override all settings for debug
            setup(DelegatingOutputStream(outStream, System.out), DelegatingOutputStream(errStream, System.err))
            println()
            println("$ (pwd) ${processBuilder.directory().absolutePath}")
            println("$ $command")
        }
        try {
            val process = processBuilder.start()

            val input = process.inputStream
            val error = process.errorStream
            val buffer = ByteArray(1000)

            while (process.isAlive) {
                // Read both stdout and stderr on the same thread
                // This is important for how Gradle outputs the logs
                if (input.available() > 0) {
                    val count = input.read(buffer)
                    outStream.write(buffer, 0, count)
                }
                if (error.available() > 0) {
                    val count = error.read(buffer)
                    errStream.write(buffer, 0, count)
                }
                Thread.sleep(1)
            }
            // Catch any other output we may have missed
            outStream.write(input.readBytes())
            errStream.write(error.readBytes())
            return process.waitFor()
        } catch (e: Exception) {
            throw PaperweightException("Failed to call git command: $command", e)
        }
    }

    fun runSilently(silenceOut: Boolean = true, silenceErr: Boolean = false): Int {
        silence(silenceOut, silenceErr)
        return run()
    }

    fun runOut(): Int {
        setup(System.out, System.err)
        return run()
    }

    fun execute() {
        val res = run()
        if (res != 0) {
            throw PaperweightException("Command finished with $res exit code: $command")
        }
    }

    fun executeSilently(silenceOut: Boolean = true, silenceErr: Boolean = false) {
        silence(silenceOut, silenceErr)
        execute()
    }

    private fun silence(silenceOut: Boolean, silenceErr: Boolean) {
        val out = if (silenceOut) null else System.out
        val err = if (silenceErr) null else System.err
        setup(out, err)
    }

    fun executeOut() {
        setup(System.out, System.err)
        execute()
    }

    fun setup(out: OutputStream? = null, err: OutputStream? = null): Command {
        outStream = out ?: UselessOutputStream
        errStream = err ?: UselessOutputStream
        return this
    }

    fun getText(): String {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        execute()
        return String(out.toByteArray(), Charset.defaultCharset())
    }

    @Suppress("unused")
    fun readText(): String? {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        return if (run() == 0) String(out.toByteArray(), Charset.defaultCharset()) else null
    }

    class Result(val exit: Int, val out: String)

    fun captureOut(logOut: Boolean): Result = run {
        val out = ByteArrayOutputStream()
        if (logOut) {
            val combined = DelegatingOutputStream(System.out, out)
            setup(combined, combined)
        } else {
            setup(out, out)
        }
        Result(run(), String(out.toByteArray()))
    }
}

fun checkoutRepoFromUpstream(
    git: Git,
    upstream: Path,
    upstreamBranch: String,
    upstreamName: String = "upstream",
    branchName: String = "master",
    ref: Boolean = false,
) {
    git("init", "--quiet").executeSilently(silenceErr = true)
    git.disableAutoGpgSigningInRepo()
    git("remote", "remove", upstreamName).runSilently(silenceErr = true)
    git("remote", "add", upstreamName, upstream.toUri().toString()).executeSilently(silenceErr = true)
    git("fetch", upstreamName, "--prune", "--prune-tags", "--force").executeSilently(silenceErr = true)
    if (git("checkout", branchName).runSilently(silenceErr = true) != 0) {
        git("checkout", "-b", branchName).runSilently(silenceErr = true)
    }
    git("reset", "--hard", if (ref) upstreamBranch else "$upstreamName/$upstreamBranch")
        .executeSilently(silenceErr = true)
    git("gc").runSilently(silenceErr = true)
}
