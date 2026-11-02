package dev.lucy.phone.configuration

/** Suggestions are a starting point, not a certification of an app's content or networking. */
data class AppSuggestion(val packageName: String, val label: String, val purpose: String)

object Onboarding {
    val suggestions = listOf(
        AppSuggestion(PolicyRules.CHROME_PACKAGE, "Chrome", "Optional web browsing; DNS filtering does not screen every page or prevent all bypasses"),
        AppSuggestion("com.google.android.calendar", "Calendar", "Appointments and reminders"),
        AppSuggestion("com.google.android.apps.authenticator2", "Authenticator", "Account sign-in codes; arrange a backup before moving accounts"),
        AppSuggestion("com.Slack", "Slack", "Work messages; review links, files, and workspace content"),
        AppSuggestion("com.openai.chatgpt", "ChatGPT", "AI assistance; content inside the app is not screened by DNS"),
    )

    fun blockers(owner: Boolean, secure: Boolean, timer: Boolean, reviewed: Boolean,
                 recoverySaved: Boolean, navigationTested: Boolean, filterPassed: Boolean): List<String> = buildList {
        if (!owner) add("Device Owner enrollment is required for daily restrictions")
        if (!secure) add("Set an Android screen-lock PIN")
        if (!timer) add("Allow Lucy's maintenance timer")
        if (!reviewed) add("Review the selected apps, sign-ins, and permissions")
        if (!recoverySaved) add("Confirm the administrator recovery code is stored offline")
        if (!navigationTested) add("Test returning Home from an app using visible navigation buttons")
        if (!filterPassed) add("Run a passing filtering check on this network")
    }
}
