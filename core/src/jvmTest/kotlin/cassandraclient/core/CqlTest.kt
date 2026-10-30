package cassandraclient.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CqlTest {
    @Test
    fun splitStatementsHonoursQuotesAndComments() {
        val script = """
            SELECT 'a;b' FROM "x;y"; -- trailing; comment
            /* only; a comment */
            CREATE FUNCTION f() CALLED ON NULL INPUT RETURNS int LANGUAGE java AS $$ return 1; $$;
        """.trimIndent()
        val statements = splitStatements(script)
        assertEquals(2, statements.size)
        assertEquals("SELECT 'a;b' FROM \"x;y\"", statements[0])
        assertEquals(true, statements[1].endsWith("$$ return 1; $$"))
    }

    @Test
    fun splitStatementsSurvivesUnterminatedString() {
        assertEquals(listOf("SELECT 'oops"), splitStatements("SELECT 'oops"))
    }

    @Test
    fun jsonArraySplitKeepsPrecisionAndNesting() {
        assertEquals(
            listOf("123456789012345678901234567890", "[1, 2]", "\"a,b\""),
            splitJsonArray("""[123456789012345678901234567890, [1, 2], "a,b"]"""),
        )
        assertFailsWith<IllegalArgumentException> { splitJsonArray("1, 2") }
    }

    @Test
    fun editableRoundTrip() {
        assertEquals("a\"b", toEditable("\"a\\\"b\"", "text"))
        assertEquals("\"a\\\"b\"", fromEditable("a\"b", "text"))
        assertEquals("9007199254740993", fromEditable(" 9007199254740993 ", "bigint"))
        assertEquals("", toEditable(null, "int"))
        assertFailsWith<IllegalArgumentException> { fromEditable("abc", "int") }
        assertFailsWith<IllegalArgumentException> { fromEditable("{bad", "list<int>") }
        assertFailsWith<IllegalArgumentException> { fromEditable("maybe", "boolean") }
    }

    @Test
    fun valueKinds() {
        assertEquals(ValueKind.STRING, valueKind("frozen<text>"))
        assertEquals(ValueKind.NUMBER, valueKind("counter"))
        assertEquals(ValueKind.JSON, valueKind("frozen<list<int>>"))
        assertEquals("text", filterValueType("map<int, text>", "CONTAINS"))
        assertEquals("int", filterValueType("map<int, text>", "CONTAINS KEY"))
        assertEquals("map<int, frozen<list<int>>>", filterValueType("frozen<map<int, frozen<list<int>>>>", "="))
    }

    @Test
    fun resources() {
        assertEquals("TABLE \"ks\".\"t\"", resourceToCql("<table ks.t>"))
        assertEquals("ALL KEYSPACES", resourceToCql("<all keyspaces>"))
        assertEquals("ROLE 'bob'", resourceToCql("<role bob>"))
        assertEquals("ALL FUNCTIONS IN KEYSPACE \"ks\"", resourceToCql("<all functions in ks>"))
        assertEquals(APPLICABLE.getValue("ALL FUNCTIONS IN KEYSPACE"), applicableFor("ALL FUNCTIONS IN KEYSPACE \"ks\""))
    }

    @Test
    fun prettyJsonKeepsNumbers() {
        assertEquals("{\n  \"a\": 123456789012345678901234567890,\n  \"b\": []\n}", prettyJson("""{"a":123456789012345678901234567890,"b":[]}"""))
    }
}
