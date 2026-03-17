package ai.releva.sdk.services.story

import ai.releva.sdk.types.response.StoryResponse
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages story trigger evaluation and display.
 * Tracks which stories have been shown by token to prevent re-display in the same session.
 */
class StoryManagerService {

    private val stories = mutableListOf<StoryResponse>()
    private val displayedStories = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val delayRunnables = mutableListOf<Runnable>()

    fun initialize(stories: List<StoryResponse>) {
        Log.d(TAG, "initialize called with ${stories.size} stories")
        this.stories.clear()
        this.stories.addAll(stories)

        // Cancel any pending delay timers from previous initialization
        for (runnable in delayRunnables) {
            handler.removeCallbacks(runnable)
        }
        delayRunnables.clear()

        setupTriggers()
    }

    private fun setupTriggers() {
        for (story in stories) {
            if (story.slides.isEmpty()) continue

            when (story.trigger) {
                "immediately" -> triggerStory(story)

                "delaySeconds" -> {
                    story.delaySeconds?.let { delay ->
                        val runnable = Runnable { triggerStory(story) }
                        delayRunnables.add(runnable)
                        handler.postDelayed(runnable, delay * 1000L)
                    }
                }

                "scrollPercentage" -> {
                    // Scroll triggers handled externally via onScrollPercentageReached()
                }

                "cartChanged" -> {
                    // Cart triggers handled externally via onCartChanged()
                }

                "wishlistChanged" -> {
                    // Wishlist triggers handled externally via onWishlistChanged()
                }

                "leaveIntent" -> {
                    Log.d(TAG, "LeaveIntent trigger not supported on mobile platforms")
                }
            }
        }
    }

    /**
     * Call when cart state changes to trigger cart-based stories.
     */
    fun onCartChanged() {
        for (story in stories) {
            if (story.trigger == "cartChanged" && !displayedStories.contains(story.token)) {
                triggerStory(story)
            }
        }
    }

    /**
     * Call when wishlist state changes to trigger wishlist-based stories.
     */
    fun onWishlistChanged() {
        for (story in stories) {
            if (story.trigger == "wishlistChanged" && !displayedStories.contains(story.token)) {
                triggerStory(story)
            }
        }
    }

    /**
     * Call when scroll percentage threshold is reached.
     */
    fun onScrollPercentageReached(percentage: Int) {
        for (story in stories) {
            if (story.trigger == "scrollPercentage" &&
                story.scrollPercentage != null &&
                percentage >= story.scrollPercentage &&
                !displayedStories.contains(story.token)
            ) {
                triggerStory(story)
            }
        }
    }

    private fun triggerStory(story: StoryResponse) {
        if (!displayedStories.contains(story.token)) {
            Log.d(TAG, "Triggering story: ${story.token}, trigger: ${story.trigger}")
            displayedStories.add(story.token)
            StoryDisplayController.showStory(story)
        }
    }

    fun dispose() {
        for (runnable in delayRunnables) {
            handler.removeCallbacks(runnable)
        }
        delayRunnables.clear()
    }

    companion object {
        private const val TAG = "StoryManager"
    }
}
