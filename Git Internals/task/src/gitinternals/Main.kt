package gitinternals

import java.io.File


fun main() {
    println("Enter .git directory location:")
    val dotGitPath = readLine().orEmpty()

    println("Enter command:")
    when (readLine().orEmpty()) {
        "list-branches" -> listBranches(dotGitPath)
        "cat-file" -> catFile(dotGitPath)
        "log" -> gitLog(dotGitPath)
        "commit-tree" -> commitTree(dotGitPath)
        else -> error("command invalid")
    }
}

fun commitTree(dotGitPath: String) {
    // must be "Enter commit-hash:" in perfect world
    println("Enter commit-hash")
    val hash = readLine().orEmpty()

    fun recTree(hash: Hash, prifix: String): String =
        when (val go = hash.hashToGitObject(dotGitPath)) {
            is StringTree -> go.data.joinToString("\n") {
                when (it.permission) {
                    "40000" -> recTree(it.hash, it.name + "/")
                    else -> prifix + it.name
                }
            }
            is StringCommit -> recTree(go.refHash, prifix)
            is StringBlob -> ""
        }

    println(recTree(hash, ""))
}


fun gitLog(dotGitPath: String) {
    println("Enter branch name:")
    val branch = readLine().orEmpty()
    val curr = File("$dotGitPath/refs/heads/$branch").readText().trim()
        .ifEmpty { File("$dotGitPath/refs/heads/master").readText().trim() }
    println(stringLog(dotGitPath, curr))
}

fun listBranches(dotGitPath: String) {
    val names = File("$dotGitPath/refs/heads").list().orEmpty()
    val curr = File("$dotGitPath/HEAD").readText().split('/')
        .last().trim().ifEmpty { "master" }
    println(names.joinToString("\n") {
        when (it) {
            curr -> "* $it"
            else -> "  $it"
        }
    })
}

fun catFile(dotGitPath: String) {
    println("Enter git object hash:")
    val hash = readLine().orEmpty().run { "${take(2)}/${substring(2)}" }
    println(hash.hashToGitObject(dotGitPath))
}