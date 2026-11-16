#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
SOURCE="$ROOT/build/upstream-swipe"
REVISION=1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a
RUNTIME_REVISION=0b0e2c5cdd67c8b4396a46ea1d1aa72ffb0128d7
NDK="${ANDROID_NDK_HOME:-$ROOT/build/toolchains/android-ndk-r27c}"
VENV="$ROOT/build/futo-venv"

if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  bash tools/setup-native.sh
fi
if [ ! -d "$SOURCE/.git" ]; then
  git clone https://gitlab.futo.org/keyboard/swipe-library.git "$SOURCE"
fi
git -C "$SOURCE" checkout --detach "$REVISION"
git -C "$SOURCE" submodule update --init --recursive
test "$(git -C "$SOURCE/third_party/executorch" rev-parse HEAD)" = "$RUNTIME_REVISION"
# Linked child lists instead of a slot per letter take the English dictionary from about 70 MB to 8 MB.
if ! git -C "$SOURCE" apply --reverse --check "$ROOT/native/trie.patch" 2>/dev/null; then
  git -C "$SOURCE" apply "$ROOT/native/trie.patch"
fi

if [ ! -x "$VENV/bin/python" ]; then
  uv venv --python 3.12 "$VENV"
fi
uv pip install --python "$VENV/bin/python" cmake==4.4.4 ninja==1.13.2 \
  pyyaml==6.0.3 typing_extensions==4.16.0 packaging==26.3
uv pip install --python "$VENV/bin/python" --no-deps torch==2.10.0
export PATH="$VENV/bin:$PATH"

python - "$SOURCE" <<'PY'
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1])
runtime = source / 'third_party/executorch'
patch = source / 'patches/executorch-xnnpack-fexceptions.patch'
applied = subprocess.run(['git', '-C', str(runtime), 'apply', '--reverse', '--check', str(patch)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if applied.returncode:
    subprocess.run(['git', '-C', str(runtime), 'apply', str(patch)], check=True)
header = runtime / 'third-party/flatcc/include/flatcc/portable/grisu3_print.h'
text = header.read_text()
header.write_text(text.replace('static char hexdigits[16] = "0123456789ABCDEF";',
                               'static char hexdigits[17] = "0123456789ABCDEF";'))
PY

make -C "$SOURCE" -o patch-et android-arm64-v8a \
  ANDROID_NDK_HOME="$NDK" VENV="$VENV" JOBS="${NATIVE_JOBS:-8}" \
  ANDROID_CMAKE_FLAGS="-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_CXX_FLAGS=-ffile-prefix-map=$ROOT=."

cmake -S "$SOURCE" -B "$SOURCE/build-android-arm64-v8a" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=$ROOT=."
cmake --build "$SOURCE/build-android-arm64-v8a" --target swipe_jni -j "${NATIVE_JOBS:-8}"

cp "$SOURCE/build-android-arm64-v8a/libswipe_jni.so" android/jniLibs/arm64-v8a/libswipe_jni.so
cp "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" android/jniLibs/arm64-v8a/libc++_shared.so
cp "$SOURCE/include/swipe_decoder/"{trie.hpp,itrie.h} native/futo/include/swipe_decoder/
ANDROID_NDK_SYSROOT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot" bash native/build.sh

python - "$SOURCE" "$ROOT" <<'PY'
from pathlib import Path
import sys

source, root = map(Path, sys.argv[1:])
runtime = source / 'third_party/executorch'
notices = [('ExecuTorch', runtime / 'LICENSE')]
for name in ('XNNPACK', 'cpuinfo', 'pthreadpool', 'FP16', 'FXdiv'):
    notices.append((name, runtime / 'backends/xnnpack/third-party' / name / 'LICENSE'))
notices.extend([
    ('flatbuffers', runtime / 'third-party/flatbuffers/LICENSE'),
    ('flatcc', runtime / 'third-party/flatcc/LICENSE'),
    ('pocketfft', runtime / 'third-party/pocketfft/LICENSE.md'),
    ('KleidiAI Apache-2.0', runtime / 'cmake-out-arm64-v8a/kleidiai-source/LICENSES/Apache-2.0.txt'),
    ('KleidiAI BSD-3-Clause', runtime / 'cmake-out-arm64-v8a/kleidiai-source/LICENSES/BSD-3-Clause.txt'),
    ('nlohmann-json', runtime / 'third-party/json/LICENSE.MIT'),
])
destination = root / 'licences/FUTO-native-dependencies.txt'
destination.write_text('\n\n'.join(name + '\n\n' + path.read_text() for name, path in notices) + '\n')
PY
