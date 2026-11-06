package wtf.mazy.peel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFileNameTest {

    private data class Case(val name: String, val displayName: String?, val typeExtension: String?, val expected: String)

    private fun assertCases(cases: List<Case>) {
        val mismatches = cases.mapNotNull { case ->
            val actual = uploadFileName(case.displayName, case.typeExtension)
            "${case.name}: expected ${case.expected}, got $actual".takeIf { actual != case.expected }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun providerNamesAreKept() = assertCases(
        listOf(
            Case("unusual extension", "translation.xliff", null, "translation.xliff"),
            Case("double extension, Cyrillic", "архив.tar.gz", "gz", "архив.tar.gz"),
            Case("type extension is ignored when named", "report.pdf", "bin", "report.pdf"),
            Case("no extension", "README", null, "README"),
            Case("leading dot", ".env", null, ".env"),
            Case("spaces inside", "my file (1).docx", null, "my file (1).docx"),
        ),
    )

    @Test
    fun unstorableNamesAreRepaired() = assertCases(
        listOf(
            Case("path is dropped", "folder/sub/a.pdf", null, "a.pdf"),
            Case("control characters", "a\u0001b\u0000c.pdf", null, "a_b_c.pdf"),
            Case("surrounding spaces", "  a.pdf  ", null, "a.pdf"),
        ),
    )

    @Test
    fun namelessFilesAreNamedFromTheirType() = assertCases(
        listOf(
            Case("no name, known type", null, "pdf", "file.pdf"),
            Case("no name, unknown type", null, null, "file"),
            Case("empty name", "", "png", "file.png"),
            Case("blank name", "   ", null, "file"),
            Case("dot", ".", null, "file"),
            Case("dot dot", "..", "txt", "file.txt"),
            Case("only a path", "folder/", null, "file"),
        ),
    )

    @Test
    fun longNamesKeepTheirExtension() {
        val name = uploadFileName("я".repeat(200) + ".xliff", null)
        assertTrue(name, name.endsWith(".xliff"))
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals("я".repeat(124) + ".xliff", name)
    }

    @Test
    fun longNamesNeverSplitACharacter() {
        val name = uploadFileName("😀".repeat(100), null)
        assertEquals("😀".repeat(63), name)
    }
}
