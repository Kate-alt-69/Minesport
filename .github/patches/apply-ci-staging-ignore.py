from pathlib import Path

path = Path('.github/workflows/build.yml')
text = path.read_text(encoding='utf-8')
old = '''on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:
'''
new = '''on:
  push:
    branches: [main]
    paths-ignore:
      - '.github/patches/**'
      - '.github/workflows/one-shot-*.yml'
  pull_request:
    branches: [main]
    paths-ignore:
      - '.github/patches/**'
      - '.github/workflows/one-shot-*.yml'
  workflow_dispatch:
'''
if text.count(old) != 1:
    raise SystemExit(f'build trigger anchor count={text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
