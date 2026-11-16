import type { Appearance, State } from "../../lib/atlas";
import { ChoicePage } from "../../lib/choice";

const options: { value: Appearance; label: string }[] = [
  { value: "system", label: "System" },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
];

export const appearanceLabel = ({ appearance }: State) => options.find(option => option.value === appearance)?.label ?? options[0].label;

export default function AppearancePage() {
  return <ChoicePage title="Appearance" subtitle="The colours of Atlas and the suggestion bar. System uses the dark theme setting of your phone."
    options={options}
    read={({ appearance }) => appearance}
    write={appearance => ({ appearance })} />;
}
