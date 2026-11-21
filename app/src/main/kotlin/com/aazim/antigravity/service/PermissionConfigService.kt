package com.aazim.antigravity.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PermissionRule(
    val action: String,
    val pattern: String,
    val mode: String
)

data class PermissionGrantsState(
    val rules: List<PermissionRule> = emptyList(),
    val autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
    val enableTerminalSandbox: Boolean = false,
    val nonWorkspaceFileAccessPolicy: String = "AGENT_SETTING_POLICY_ALLOW",
    val planReviewPolicy: String = "Always Proceed",
    val securityPreset: String = "Turbo Mode"
) {
    fun getRulesForAction(action: String): List<PermissionRule> =
        rules.filter { it.action.equals(action, ignoreCase = true) }

    fun getCountForAction(action: String): Int =
        rules.count { it.action.equals(action, ignoreCase = true) }

    val toolPermissionsCount: Int
        get() = rules.count { it.action in listOf("read_file", "write_file", "command", "mcp") }

    val networkPermissionsCount: Int
        get() = rules.count { it.action == "read_url" }

    val commandsOutsideSandboxCount: Int
        get() = rules.count { it.action == "unsandboxed" }

    val browserActuationCount: Int
        get() = rules.count { it.action == "execute_url" }
}

object PermissionConfigService {
    private const val TAG = "PermissionConfig"
    private val GRANT_REGEX = Regex("^([^(]+)\\((.*)\\)$")

    private fun getCandidateFiles(context: Context): List<File> {
        val list = mutableListOf<File>()
        list.add(File(context.filesDir, ".gemini/config/config.json"))
        list.add(File(context.filesDir, "home/.gemini/config/config.json"))
        return list
    }

    private fun findPrimaryConfigFile(context: Context): File {
        val candidates = getCandidateFiles(context)
        for (f in candidates) {
            if (f.exists() && f.canRead()) return f
        }
        val defaultFile = candidates.first()
        defaultFile.parentFile?.mkdirs()
        return defaultFile
    }

    suspend fun loadPermissions(context: Context): PermissionGrantsState = withContext(Dispatchers.IO) {
        val file = findPrimaryConfigFile(context)
        if (!file.exists() || !file.canRead()) {
            return@withContext PermissionGrantsState()
        }
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isBlank() || !content.startsWith("{")) {
                return@withContext PermissionGrantsState()
            }
            val root = JSONObject(content)
            val userSettings = root.optJSONObject("userSettings") ?: JSONObject()

            val autoExec = userSettings.optString("autoExecutionPolicy", "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER")
            val sandbox = userSettings.optBoolean("enableTerminalSandbox", false)
            val nonWorkspaceFile = userSettings.optString("nonWorkspaceFileAccessPolicy", "AGENT_SETTING_POLICY_ALLOW")
            val planReview = userSettings.optString("planReviewPolicy", "Always Proceed")
            val secPreset = userSettings.optString("securityPreset", "Turbo Mode")

            val rules = mutableListOf<PermissionRule>()
            val grantsObj = userSettings.optJSONObject("globalPermissionGrants")
            if (grantsObj != null) {
                fun parseList(key: String, mode: String) {
                    val arr = grantsObj.optJSONArray(key) ?: return
                    for (i in 0 until arr.length()) {
                        val grantStr = arr.optString(i, "").trim()
                        val match = GRANT_REGEX.find(grantStr)
                        if (match != null) {
                            val action = match.groupValues[1].trim()
                            val pattern = match.groupValues[2].trim()
                            if (action.isNotBlank() && pattern.isNotBlank()) {
                                rules.add(PermissionRule(action, pattern, mode))
                            }
                        }
                    }
                }
                parseList("allow", "allow")
                parseList("ask", "ask")
                parseList("deny", "deny")
            }

            PermissionGrantsState(
                rules = rules,
                autoExecutionPolicy = autoExec,
                enableTerminalSandbox = sandbox,
                nonWorkspaceFileAccessPolicy = nonWorkspaceFile,
                planReviewPolicy = planReview,
                securityPreset = secPreset
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load permissions: ${e.message}", e)
            PermissionGrantsState()
        }
    }

    suspend fun savePermissions(
        context: Context,
        state: PermissionGrantsState
    ): Boolean = withContext(Dispatchers.IO) {
        val file = findPrimaryConfigFile(context)
        try {
            file.parentFile?.mkdirs()
            val root = if (file.exists() && file.canRead()) {
                val text = file.readText(Charsets.UTF_8).trim()
                if (text.startsWith("{")) JSONObject(text) else JSONObject()
            } else {
                JSONObject()
            }

            val userSettings = root.optJSONObject("userSettings") ?: JSONObject().also { root.put("userSettings", it) }
            userSettings.put("autoExecutionPolicy", state.autoExecutionPolicy)
            userSettings.put("enableTerminalSandbox", state.enableTerminalSandbox)
            userSettings.put("nonWorkspaceFileAccessPolicy", state.nonWorkspaceFileAccessPolicy)
            userSettings.put("planReviewPolicy", state.planReviewPolicy)
            userSettings.put("securityPreset", state.securityPreset)

            val allowArr = JSONArray()
            val askArr = JSONArray()
            val denyArr = JSONArray()

            for (rule in state.rules) {
                val grantStr = "${rule.action}(${rule.pattern})"
                when (rule.mode.lowercase()) {
                    "allow" -> allowArr.put(grantStr)
                    "ask" -> askArr.put(grantStr)
                    "deny" -> denyArr.put(grantStr)
                    else -> allowArr.put(grantStr)
                }
            }

            val grantsObj = JSONObject()
            grantsObj.put("allow", allowArr)
            grantsObj.put("ask", askArr)
            grantsObj.put("deny", denyArr)
            userSettings.put("globalPermissionGrants", grantsObj)

            val formattedJson = root.toString(2)

            for (target in getCandidateFiles(context)) {
                try {
                    target.parentFile?.mkdirs()
                    target.writeText(formattedJson, Charsets.UTF_8)
                    target.setReadable(true, false)
                    target.setWritable(true, false)
                } catch (e: Exception) {}
            }
            Log.i(TAG, "Permissions saved successfully (${state.rules.size} rules)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permissions: ${e.message}", e)
            false
        }
    }
}

