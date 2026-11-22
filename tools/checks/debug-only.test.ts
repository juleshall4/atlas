// Debug-only code lives in android/debug and only tools/debug.sh copies it into the build.
// These fail if a debug build left it behind, so it can never be committed or released.
import { expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";

const root = new URL("../../", import.meta.url);

test("debug-only Kotlin is not in the build sources", () => {
  expect(existsSync(new URL("android/kotlin/com/atlas/debug", root))).toBe(false);
});

test("the manifest has no debug-only parts", () => {
  const manifest = readFileSync(new URL("android/src/main/AndroidManifest.xml", root), "utf8");
  expect(manifest).not.toContain("com.atlas.debug");
});
