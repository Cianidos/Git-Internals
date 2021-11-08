package gitinternals

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.InflaterInputStream

sealed class StringGit(val hash: Hash)

class StringBlob(hash: Hash, private val data: String) : StringGit(hash) {
    override fun toString(): String = "*BLOB*\n$data"
}

class StringTree(hash: Hash, data: String) : StringGit(hash) {
    data class Node(
        val permission: String, val name: String, val hash: Hash,
        val damaged: Hash
    ) {
        override fun toString(): String = "$permission $damaged $name"
    }

    // 4 because of damaged hash are using in tests
    val data = treeFormatInfoLines(data).chunked(4)
        .map { Node(it[0], it[1], it[2], it[3]) }

    override fun toString(): String =
        "*TREE*\n" + data.joinToString("\n")
}

infix fun String.splitAt(n: Int) = listOf(take(n), this.drop(n + 1))
infix fun String.splitAt(ch: Char) = splitAt(indexOf(ch))
infix fun String.splitBefore(n: Int) = listOf(take(n), this.drop(n))
infix fun <A> List<A>.lastFlatMap(f: (A) -> List<A>) =
    dropLast(1) + f(last())

infix fun <A> List<A>.firstMap(f: (A) -> A) = listOf(f(first())) + drop(1)
infix fun <A> List<A>.firstFlatMap(f: (A) -> List<A>) = f(first()) + drop(1)

private fun treeFormatInfoLines(data: String): List<String> =
    if (data.isEmpty()) emptyList() else data splitAt ' ' lastFlatMap {
        it splitAt 0.toChar() lastFlatMap {
            it splitBefore 20 firstFlatMap { rawHash ->
                listOf(
                    rawHash.map { it.code.toByte() } // normal hash
                        .joinToString("") { "%02x".format(it) },
                    rawHash.map { it.code.toByte() } // damaged hash without leading zeroes
                        .joinToString("") { "%x".format(it) })
            } lastFlatMap { treeFormatInfoLines(it) }
        }
    }

fun stringLog(dotGitPath: String, hash: Hash): String {
    return when (val gitCommit = hash.hashToGitObject(dotGitPath)) {
        is StringCommit -> gitCommit.run {
            "Commit: $hash\n$committer\n${message}" + if (parents.isNotEmpty()) "\n\n" +
                    stringLog(dotGitPath, parents.first()) else ""
        }
        else -> error("Commit expected")
    }
}

fun Iterable<String>.trickyFind(
    string: String, suffix: String = " ", f: (String) -> String = { it }
) = this.filter { it.contains(string) }
    .joinToString(" ", transform = { f(it).removePrefix("$string$suffix") })

class StringCommit(hash: Hash, data: String) : StringGit(hash) {
    private val lines = data.trim().lines()
    val refType = lines.first().splitAt(' ')[0]
    val refHash = lines.first().splitAt(' ')[1]
    val parents = lines.filter { it.contains("parent") }
        .map { it.removePrefix("parent ") }
    val committer = lines.trickyFind("committer") { parsePerson(it) }
    val author = lines.trickyFind("author") { parsePerson(it) }
    val message = lines.takeLastWhile { it.isNotBlank() }.joinToString("\n")
    override fun toString() =
        "*COMMIT*\n$refType: $refHash\nparents: ${parents.joinToString(" ")}\nauthor: $author\ncommitter: $committer\ncommit message:\n$message"
}

infix fun String.regexFindIn(string: String) =
    this.toRegex().find(string)?.value.orEmpty()

private fun parsePerson(string: String): String {
    val epochSeconds = "(?<=>\\s)\\d*(?=\\s+)" regexFindIn string
    val zoneOffset = "[\\+\\-]\\d\\d\\d\\d" regexFindIn string
    val name = ".*(?=\\s<)" regexFindIn string
    val mail = "(?<=<).+@.+(?=>)" regexFindIn string
    val timestamp = Instant.ofEpochSecond(epochSeconds.toLong())
        .atOffset(ZoneOffset.of(zoneOffset))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))!!
    return if (string.startsWith("committer"))
        "$name $mail commit timestamp: $timestamp"
    else "$name $mail original timestamp: $timestamp"
}

typealias Hash = String

fun Hash.hashToGitObject(dotGitPath: String): StringGit {
    this.trim().run trimmed@{
        val (head, tail) = this splitBefore 2
        val gitObject = File("$dotGitPath/objects/$head/$tail")
        return InflaterInputStream(gitObject.inputStream()).use { input ->
            input.readBytes().map { Char(it.toUShort()) }.joinToString("").run {
                when (splitAt(' ')[0]) {
                    "commit" -> ::StringCommit
                    "blob" -> ::StringBlob
                    "tree" -> ::StringTree
                    else -> error("Unknown Git object")
                }(this@trimmed, splitAt('\u0000')[1])
            }
        }
    }
}
