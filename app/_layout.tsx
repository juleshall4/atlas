import { setColourScheme, Slot, useSnapshot } from "ink";
import { useEffect } from "react";
import { state } from "../lib/atlas";

/** Keeps every screen in the colours that Appearance chooses. */
export default function Layout() {
  const result = useSnapshot(state);
  const dark = result.status === "ready" ? result.data.dark : null;
  useEffect(() => {
    if (dark !== null) setColourScheme(dark ? "dark" : "light");
  }, [dark]);
  return <Slot />;
}
