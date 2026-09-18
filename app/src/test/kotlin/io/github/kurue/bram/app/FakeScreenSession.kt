package io.github.kurue.bram.app

/** A [ScreenSession] that records what it was asked to do, for tool tests without a device. */
class FakeScreenSession(
    var snapshot: ScreenSnapshot? = null,
    var tapResult: ScreenActionResult = ScreenActionResult.NotFound,
    var typeResult: ScreenActionResult = ScreenActionResult.NotFound,
    var scrollResult: ScreenActionResult = ScreenActionResult.NotFound,
) : ScreenSession {

    val taps = mutableListOf<ScreenTarget>()
    var typed: Pair<String, ScreenTarget?>? = null
    var scrolled: Pair<ScrollDirection, ScreenTarget?>? = null

    override suspend fun snapshot(): ScreenSnapshot? = snapshot

    override suspend fun tap(target: ScreenTarget): ScreenActionResult {
        taps += target
        return tapResult
    }

    override suspend fun typeText(text: String, target: ScreenTarget?): ScreenActionResult {
        typed = text to target
        return typeResult
    }

    override suspend fun scroll(direction: ScrollDirection, target: ScreenTarget?): ScreenActionResult {
        scrolled = direction to target
        return scrollResult
    }
}
