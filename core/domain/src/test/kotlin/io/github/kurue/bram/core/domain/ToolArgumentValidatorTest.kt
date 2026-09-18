package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgumentValidatorTest {

    private val schema = """
        {"type":"object",
         "properties":{
           "path":{"type":"string"},
           "count":{"type":"integer"},
           "force":{"type":"boolean"}},
         "required":["path"],
         "additionalProperties":false}
    """.trimIndent()

    @Test
    fun `valid arguments pass`() {
        assertNull(ToolArgumentValidator.validate(schema, """{"path":"notes/todo.txt","count":2}"""))
    }

    @Test
    fun `a missing required argument is named`() {
        val problem = ToolArgumentValidator.validate(schema, """{"count":2}""")
        assertNotNull(problem)
        assertEquals("Missing required argument \"path\".", problem)
    }

    @Test
    fun `an unknown argument is named`() {
        val problem = ToolArgumentValidator.validate(schema, """{"path":"a.txt","colour":"red"}""")
        assertEquals("Unknown argument \"colour\".", problem)
    }

    @Test
    fun `a mistyped argument is named`() {
        val problem = ToolArgumentValidator.validate(schema, """{"path":"a.txt","count":"two"}""")
        assertEquals("Argument \"count\" should be of type integer.", problem)
    }

    @Test
    fun `arguments that are not an object are refused`() {
        assertNotNull(ToolArgumentValidator.validate(schema, """["path"]"""))
        assertNotNull(ToolArgumentValidator.validate(schema, "{not json"))
    }

    @Test
    fun `an empty schema accepts anything object-shaped`() {
        assertNull(ToolArgumentValidator.validate("{}", """{"anything":"goes"}"""))
    }
}
