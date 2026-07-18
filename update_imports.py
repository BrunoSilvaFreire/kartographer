import os

project_dir = "/home/brunorbsf/MiscProjects/kartographer"

for root, dirs, files in os.walk(project_dir):
    if ".git" in root or "build" in root or ".gradle" in root:
        continue
    for f in files:
        if f.endswith(".kt") or f.endswith(".kts"):
            path = os.path.join(root, f)
            with open(path, "r") as file:
                content = file.read()
            
            if "import io.kartographer.core.*" in content and "import io.kartographer.core.model.*" not in content:
                new_content = content.replace("import io.kartographer.core.*", "import io.kartographer.core.*\nimport io.kartographer.core.model.*")
                with open(path, "w") as file:
                    file.write(new_content)
                print(f"Updated wildcard {path}")
