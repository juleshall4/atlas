import { useEffect } from "react";
import { type HapticPreset, type State, previewTouchpad } from "../../lib/atlas";
import { ChoicePage } from "../../lib/choice";

const options: { value: HapticPreset | "off"; label: string }[] = [
  { value: "off", label: "Off" },
  { value: "normal", label: "Normal" },
  { value: "strong", label: "Strong" },
];

export const vibrationLabel = ({ haptics, hapticPreset }: State) =>
  haptics ? options.find(option => option.value === hapticPreset)?.label ?? "Normal" : "Off";

export default function Vibration() {
  useEffect(previewTouchpad, []);
  return <ChoicePage title="Vibration"
    subtitle="Swipe across the letter keys to try it."
    options={options}
    read={({ haptics, hapticPreset }) => haptics ? hapticPreset : "off"}
    write={value => value === "off" ? { haptics: false } : { haptics: true, hapticPreset: value }} />;
}
