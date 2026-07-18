val regex = Regex(".*(?:/|^)karto(?:/.*)?\\.kts")
val paths = listOf(
    "/a/b/karto.kts",
    "karto.kts",
    "/a/b/karto/foo.kts",
    "karto/foo.kts",
    "/a/b/notkarto.kts",
    "notkarto/foo.kts"
)
for (path in paths) {
    println("$path -> ${regex.matches(path)}")
}
