import { back, navigate, replace } from "ink";
import { transferLearning } from "./atlas";

const finished = { export: "/settings/data/exported", import: "/settings/data/imported" } as const;
const failed = { export: "/settings/data/export-failed", import: "/settings/data/import-failed" } as const;

/** Opens the file picker. Cancelling it stays on the current page. */
export async function transfer(mode: "export" | "import", from: "data" | "failure") {
  try {
    if (!await transferLearning(mode)) return;
  } catch {
    if (from === "data") navigate(failed[mode]);
    return;
  }
  // The result has no way back, so it starts a new stack.
  back();
  if (from === "failure") back();
  replace(finished[mode]);
}

/** Leaves a result page for Your words. */
export function returnToData() {
  replace("/");
  navigate("/settings/data");
}
