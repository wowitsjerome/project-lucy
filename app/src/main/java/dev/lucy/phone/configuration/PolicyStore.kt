package dev.lucy.phone.configuration

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device-protected policy permits restoring restrictions before the user unlocks. No credentials live here. */
class PolicyStore(context: Context) {
    private val file = AtomicFile(File(context.createDeviceProtectedStorageContext().filesDir, "policy-state.json"))
    @Synchronized fun read(): JSONObject = if (!file.baseFile.exists()) JSONObject() else
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    @Synchronized fun update(block: (JSONObject) -> Unit) {
        val state = read(); block(state)
        val output = file.startWrite()
        try { output.write(state.toString(2).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun policy() = read().optJSONObject("effective")?.let(DevicePolicy::fromJson)
    fun draft() = read().optJSONObject("draft")?.let(DevicePolicy::fromJson)
    fun onboarding() = read().optJSONObject("onboarding") ?: JSONObject()
    fun configured() = read().optBoolean("activated", false)
    fun maintenance(): MaintenanceWindow? = read().optJSONObject("maintenance")?.let {
        MaintenanceWindow(it.getLong("deadline"), it.getInt("boot"))
    }
    fun errors() = read().optJSONArray("errors")?.strings()?.toList() ?: emptyList()
    fun setErrors(errors: List<String>) = update { it.put("errors", JSONArray(errors)) }
    fun log(event: String) = update { state ->
        val previous = state.optJSONArray("log") ?: JSONArray()
        val next = JSONArray()
        for (i in maxOf(0, previous.length() - 99) until previous.length()) next.put(previous.get(i))
        next.put(JSONObject().put("at", System.currentTimeMillis()).put("event", event))
        state.put("log", next)
    }
}
