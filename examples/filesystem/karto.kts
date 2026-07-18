import io.kartographer.tasks.*
import io.kartographer.misc.FilePaths

task<Copy>("copy-task") {
    sources = FilePaths(listOf("src/a.txt"))
    destination = "dest"
}

task<Copy>("copy-dir") {
    sources = FilePaths(listOf("src_dir"))
    destination = "dest_dir"
}

task<Copy>("copy-filtered") {
    sources = FilePaths(listOf("src_dir"))
    destination = "dest_dir_filtered"
    filter = io.kartographer.misc.FileFilter(includes = listOf("*.txt"))
}

task<Copy>("copy-no-dest") {
    sources = FilePaths(listOf("src/a.txt"))
}

task<Copy>("copy-no-source") {
    sources = FilePaths(listOf("nonexistent.txt"))
    destination = "dest"
}

task<Sync>("sync-task") {
    sources = FilePaths(listOf("src/a.txt"))
    destination = "dest"
}

task<Sync>("sync-dir") {
    sources = FilePaths(listOf("src_dir"))
    destination = "dest_dir"
}

task<Sync>("sync-filtered") {
    sources = FilePaths(listOf("src_dir"))
    destination = "dest_dir_sync_filtered"
    filter = io.kartographer.misc.FileFilter(includes = listOf("*.txt"))
}

task<Sync>("sync-no-dest") {
    sources = FilePaths(listOf("src/a.txt"))
}

task<Sync>("sync-no-source") {
    sources = FilePaths(listOf("nonexistent.txt"))
    destination = "dest"
}

task<Move>("move-task") {
    sources = FilePaths(listOf("src/a.txt"))
    destination = "dest"
}

task<Move>("move-dir") {
    sources = FilePaths(listOf("src_dir"))
    destination = "dest_dir"
}

task<Move>("move-no-dest") {
    sources = FilePaths(listOf("src/a.txt"))
}

task<Move>("move-no-source") {
    sources = FilePaths(listOf("nonexistent.txt"))
    destination = "dest"
}

task<Delete>("delete-task") {
    files = FilePaths(listOf("src/a.txt"))
}

task<Delete>("delete-dir") {
    files = FilePaths(listOf("src_dir"))
}

task<Delete>("delete-nonexistent") {
    files = FilePaths(listOf("nonexistent_dir"))
}

task<Copy>("copy-file-filtered-skip") {
    sources = FilePaths(listOf("src/b.conf"))
    destination = "dest_skip"
    filter = io.kartographer.misc.FileFilter(includes = listOf("*.txt"))
}

task<Sync>("sync-file-filtered-skip") {
    sources = FilePaths(listOf("src/b.conf"))
    destination = "dest_sync_skip"
    filter = io.kartographer.misc.FileFilter(includes = listOf("*.txt"))
}
