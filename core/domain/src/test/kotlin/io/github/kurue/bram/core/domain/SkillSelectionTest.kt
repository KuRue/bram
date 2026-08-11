package io.github.kurue.bram.core.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SkillSelectionTest {
    private fun skill(id: String, description: String, version: String = "1.0.0") =
        ActiveSkill(id = id, name = id, version = version, description = description, instructions = "steps")

    private fun embedder(map: Map<String, FloatArray>): Embedder = object : Embedder {
        override suspend fun embed(text: String): FloatArray? = map[text]
    }

    @Test
    fun `returns input order when there is nothing to choose between`() = runBlocking {
        val ranker = SkillSelection()
        val only = listOf(skill("a", "x"))
        assertSame(only, ranker.rank(only, "q", embedder(emptyMap())))
    }

    @Test
    fun `returns input order when no embedder is given`() = runBlocking {
        val skills = listOf(skill("git", "how to use git"), skill("news", "summarize the news"))
        val ranked = SkillSelection().rank(skills, "commit my changes", embedder = null)
        assertEquals(skills, ranked)
    }

    @Test
    fun `returns input order when the embedder returns null for the query`() = runBlocking {
        val skills = listOf(skill("git", "g"), skill("news", "n"))
        val ranker = SkillSelection()
        val embedder = embedder(mapOf("g" to floatArrayOf(1f), "n" to floatArrayOf(0f)))
        // Query has no vector, so ranking cannot run.
        assertEquals(skills, ranker.rank(skills, "unmatched query", embedder))
    }

    @Test
    fun `orders by cosine similarity to the query`() = runBlocking {
        val git = skill("git", "version control with git")
        val news = skill("news", "summarize the daily news")
        val weather = skill("weather", "the forecast and current conditions")
        // Orthogonal-ish vectors so cosine tracks the matching axis only.
        val embedder = embedder(
            mapOf(
                "version control with git" to floatArrayOf(1f, 0f, 0f),
                "summarize the daily news" to floatArrayOf(0f, 1f, 0f),
                "the forecast and current conditions" to floatArrayOf(0f, 0f, 1f),
                "commit my staged changes" to floatArrayOf(1f, 0f, 0f),
            ),
        )
        val ranked = SkillSelection().rank(listOf(news, weather, git), "commit my staged changes", embedder)
        assertEquals(listOf("git", "news", "weather"), ranked.map { it.id })
    }

    @Test
    fun `keeps input order when one description fails to embed`() = runBlocking {
        val a = skill("a", "known")
        val b = skill("b", "unknown")
        val embedder = embedder(mapOf("known" to floatArrayOf(1f), "query" to floatArrayOf(1f)))
        val skills = listOf(a, b)
        val ranked = SkillSelection().rank(skills, "query", embedder)
        // Rather than drop b or half-rank, the ranker declines and returns the input order.
        assertEquals(skills, ranked)
    }

    @Test
    fun `caches description embeddings and re-embeds after a version change`() = runBlocking {
        val calls = mutableListOf<String>()
        val embedder = object : Embedder {
            override suspend fun embed(text: String): FloatArray? {
                calls += text
                return floatArrayOf(1f)
            }
        }
        val ranker = SkillSelection()
        // Two skills so the size <= 1 short-circuit does not skip embedding entirely.
        ranker.rank(listOf(skill("a", "da", "1.0.0"), skill("b", "db", "1.0.0")), "q", embedder)
        ranker.rank(listOf(skill("a", "da", "1.0.0"), skill("b", "db", "1.0.0")), "q", embedder)
        ranker.rank(listOf(skill("a", "da", "1.1.0"), skill("b", "db", "1.0.0")), "q", embedder)
        // "da" embeds on turn 1 (cache miss) and again on turn 3 (version changed); turn 2 is a hit.
        // "db" is unchanged across all three turns, so it embeds exactly once.
        assertEquals(2, calls.count { it == "da" })
        assertEquals(1, calls.count { it == "db" })
    }
}
