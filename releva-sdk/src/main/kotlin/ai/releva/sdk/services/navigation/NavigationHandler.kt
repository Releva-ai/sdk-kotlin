package ai.releva.sdk.services.navigation

import android.content.Intent
import android.os.Bundle

/**
 * Interface for handling push notification navigation
 * Apps must implement this to provide custom navigation logic
 */
interface NavigationHandler {
    /**
     * Navigate to a specific screen
     * @param screenName The name of the screen to navigate to
     * @param parameters Optional parameters to pass to the screen
     */
    fun navigateToScreen(screenName: String, parameters: Bundle)

    /**
     * Get the screen name mappings
     * @return Map of screen names to their identifiers (e.g., fragment IDs, route names)
     */
    fun getScreenMappings(): Map<String, Any>
}
