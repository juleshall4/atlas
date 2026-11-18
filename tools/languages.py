#!/usr/bin/env python3
"""Builds the downloadable typing languages from the HeliBoard AOSP dictionaries.

Each word list is cut to its most common words, written in one format and compressed. The script writes:

- the files on the guide site's Downloads page, in docs/public/downloads/languages/
- the list on the Languages download page, in docs/src/data/languages.json
- the list Atlas checks a downloaded file against, in android/kotlin/com/atlas/engine/LanguageFiles.java

Usage: python3 tools/languages.py <aosp-dictionaries checkout>

The checkout is https://codeberg.org/Helium314/aosp-dictionaries at the commit in SOURCE_COMMIT.
"""
import gzip
import hashlib
import io
import json
import re
import subprocess
import sys
from pathlib import Path

SOURCE_COMMIT = "ff0cd52be8d9530200fefe8b296c53d4f662091e"
# More words take longer to load and more memory on the phone, and the rarest words are seldom typed.
MOST_WORDS = 250_000
# Bump when a file changes, so a new file never has an old file's name.
VERSION = 1

DICTIONARIES = "https://codeberg.org/Helium314/aosp-dictionaries"
AOSP = ("AOSP", DICTIONARIES, "Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0")
LEIPZIG = ("Leipzig Corpora", "https://wortschatz.uni-leipzig.de/en/download", "CC BY 4.0",
           "https://creativecommons.org/licenses/by/4.0/")

# code, name, word list, one-letter words, (source, source link, licence, licence link)
LANGUAGES = [
    ("hr", "Croatian", "main_hr", "i u a s o k", AOSP),
    ("cs", "Czech", "main_cs", "a v z s i o k u", AOSP),
    ("da", "Danish", "main_da", "i å", AOSP),
    ("nl", "Dutch", "main_nl", "u", AOSP),
    ("fi", "Finnish", "main_fi", "", AOSP),
    ("fr", "French", "main_fr", "à a y", AOSP),
    ("de", "German", "main_de", "", LEIPZIG),
    ("hi-latn", "Hinglish", "main_hi_ZZ", "a i", ("HeliBoard community", DICTIONARIES, None, None)),
    ("hu", "Hungarian", "main_hu", "a s e ő ó", AOSP),
    ("it", "Italian", "main_it", "e a è i o", AOSP),
    ("nb", "Norwegian", "main_nb", "i å", AOSP),
    ("pl", "Polish", "main_pl", "w i z a o u", AOSP),
    ("pt-BR", "Portuguese (Brazil)", "main_pt_BR", "e a o à é", AOSP),
    ("pt-PT", "Portuguese (Portugal)", "main_pt_PT", "e a o à é", AOSP),
    ("ro", "Romanian", "main_ro", "a o e î", AOSP),
    ("es", "Spanish", "main_es", "y a o e u", AOSP),
    ("sv", "Swedish", "main_sv", "i å ö", AOSP),
    ("tr", "Turkish", "main_tr__legacy", "o", AOSP),
]

LINE = re.compile(r"word=([^,]+),\s*f=(\d+)(?:,\s*flags=([^,]*))?")


def words(path):
    """The list's words, most common first, without its pairs of words or shortcuts."""
    found = {}
    for line in path.open(encoding="utf-8"):
        match = LINE.match(line.strip())
        if not match:
            continue
        word, frequency, flags = match.group(1), int(match.group(2)), match.group(3) or ""
        if word not in found or found[word][0] < frequency:
            found[word] = (frequency, flags)
    ranked = sorted(found.items(), key=lambda item: -item[1][0])
    return ranked[:MOST_WORDS]


def build(checkout, root):
    head = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != SOURCE_COMMIT:
        sys.exit(f"The checkout is at {head}, not {SOURCE_COMMIT}")
    downloads = root / "docs/public/downloads/languages"
    downloads.mkdir(parents=True, exist_ok=True)
    for old in downloads.iterdir():
        old.unlink()
    table, java = [], []
    for code, name, source, singles, (origin, origin_link, licence, licence_link) in sorted(LANGUAGES, key=lambda item: item[1]):
        listed = words(checkout / "wordlists" / f"{source}.combined")
        text = io.StringIO()
        text.write(f"dictionary=main:{code},locale={code},description={name}\n")
        for word, (frequency, flags) in listed:
            text.write(f" word={word},f={frequency},flags={flags}\n")
        packed = io.BytesIO()
        with gzip.GzipFile(fileobj=packed, mode="wb", compresslevel=9, mtime=0, filename="") as output:
            output.write(text.getvalue().encode("utf-8"))
        data = packed.getvalue()
        file = f"atlas-{code.lower()}-{VERSION}.atlas"
        (downloads / file).write_bytes(data)
        sha256 = hashlib.sha256(data).hexdigest()
        table.append({"name": name, "file": file, "size": size(len(data)), "source": origin,
                      "sourceLink": origin_link, "licence": licence, "licenceLink": licence_link})
        letters = ", ".join(f'"{letter}"' for letter in singles.split())
        java.append(f'            new TypingLanguages.Language("{code}", "{name}", "{file}", "{sha256}", {len(data)}L, false, true, Set.of({letters}))')
        print(f"{name:24} {len(listed):7} words  {size(len(data)):>6}")
    data_dir = root / "docs/src/data"
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "languages.json").write_text(json.dumps(table, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (root / "android/kotlin/com/atlas/engine/LanguageFiles.java").write_text(
        "package com.atlas.engine;\n\nimport java.util.List;\nimport java.util.Set;\n\n"
        "/** The language files on the Downloads page. Generated by tools/languages.py, so edit that instead. */\n"
        "final class LanguageFiles {\n"
        "    static final List<TypingLanguages.Language> ALL = List.of(\n" + ",\n".join(java) + ");\n\n"
        "    private LanguageFiles() {}\n}\n", encoding="utf-8")


def size(count):
    megabytes = count / 1_000_000
    return f"{megabytes:.1f} MB" if megabytes < 10 else f"{megabytes:.0f} MB"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    build(Path(sys.argv[1]), Path(__file__).resolve().parent.parent)
