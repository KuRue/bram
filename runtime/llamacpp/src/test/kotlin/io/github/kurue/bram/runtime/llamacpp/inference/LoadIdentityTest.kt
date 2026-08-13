package io.github.kurue.bram.runtime.llamacpp.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadIdentityTest {

    private fun request(
        modelId: String = "local:abc",
        contextTokens: Int = 8_192,
        gpuLayers: Int = 999,
        deviceFilter: String = "HTP",
        threads: Int = 0,
        cpuMask: String = "",
        cpuStrict: Boolean = false,
        poll: Int = -1,
        loadMode: String = "auto",
        batchTokens: Int = 512,
        ubatchTokens: Int = 128,
        hexUseHmx: Boolean = false,
    ): JSONObject = JSONObject()
        .put("modelId", modelId)
        .put("contextTokens", contextTokens)
        .put("gpuLayers", gpuLayers)
        .put("deviceFilter", deviceFilter)
        .put("threads", threads)
        .put("cpuMask", cpuMask)
        .put("cpuStrict", cpuStrict)
        .put("poll", poll)
        .put("loadMode", loadMode)
        .put("batchTokens", batchTokens)
        .put("ubatchTokens", ubatchTokens)
        .put("hexUseHmx", hexUseHmx)

    @Test
    fun `two identical requests produce equal identities`() {
        assertEquals(LoadIdentity.from(request()), LoadIdentity.from(request()))
    }

    @Test
    fun `any field difference breaks the identity`() {
        val baseline = request()
        val other = LoadIdentity.from(request(gpuLayers = 7))
        assertNotEquals(LoadIdentity.from(baseline), other)
        // The fields that gate a reload: backend, offload, attention, KV, batch, threadpool,
        // load mode, and the hexagon flags — a change in any one must mean a fresh context.
        assertEquals(
            LoadIdentity.from(request()),
            LoadIdentity.from(request().put("threads", 0)),
        )
        assertNotEquals(
            LoadIdentity.from(request(threads = 4)),
            LoadIdentity.from(request(threads = 6)),
        )
        assertNotEquals(
            LoadIdentity.from(request(cpuMask = "3")),
            LoadIdentity.from(request(cpuMask = "")),
        )
        assertNotEquals(
            LoadIdentity.from(request(hexUseHmx = true)),
            LoadIdentity.from(request(hexUseHmx = false)),
        )
        assertNotEquals(
            LoadIdentity.from(request(loadMode = "no_mmap")),
            LoadIdentity.from(request(loadMode = "mmap")),
        )
    }

    @Test
    fun `threads of zero resolve to the device core count and clamps to it`() {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        assertEquals(cores, LoadIdentity.from(request()).threads)
        assertEquals(cores, LoadIdentity.from(request(threads = cores)).threads)
        assertEquals(cores, LoadIdentity.from(request(threads = 10_000)).threads)
    }

    @Test
    fun `batch values clamp to the context and ubatch to the batch`() {
        val identity = LoadIdentity.from(
            request(contextTokens = 1_024, batchTokens = 8_192, ubatchTokens = 8_192),
        )
        assertEquals(1_024, identity.batchTokens)
        assertEquals(1_024, identity.ubatchTokens)
    }

    @Test
    fun `the mask is canonical lowercase hex and strictness needs a mask`() {
        assertEquals("fc", LoadIdentity.from(request(cpuMask = "0xFC")).cpuMask)
        assertEquals("3f", LoadIdentity.from(request(cpuMask = "0x3f")).cpuMask)
        assertEquals("", LoadIdentity.from(request(cpuMask = "not-a-mask")).cpuMask)
        assertFalse(LoadIdentity.from(request(cpuStrict = true, cpuMask = "")).cpuStrict)
        assertTrue(LoadIdentity.from(request(cpuStrict = true, cpuMask = "3")).cpuStrict)
    }

    @Test
    fun `the hex key is stable and distinguishes every flag`() {
        val defaults = LoadIdentity.from(request()).hexKey
        assertEquals(defaults, LoadIdentity.from(request()).hexKey)
        assertNotEquals(defaults, LoadIdentity.from(request(hexUseHmx = true)).hexKey)
        assertNotEquals(defaults, request().put("hexOpBatch", 1).let { LoadIdentity.from(it).hexKey })
        assertNotEquals(defaults, request().put("hexNDev", 2).let { LoadIdentity.from(it).hexKey })
    }
}
