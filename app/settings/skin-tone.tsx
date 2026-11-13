import type { SkinTone, State } from "../../lib/atlas";
import { ChoicePage } from "../../lib/choice";

const tones: { value: SkinTone; label: string }[] = [
  { value: "default", label: "👋 Default" },
  { value: "light", label: "👋🏻 Light" },
  { value: "medium-light", label: "👋🏼 Medium light" },
  { value: "medium", label: "👋🏽 Medium" },
  { value: "medium-dark", label: "👋🏾 Medium dark" },
  { value: "dark", label: "👋🏿 Dark" },
];

export const skinToneLabel = ({ skinTone }: State) => tones.find(tone => tone.value === skinTone)?.label ?? tones[0].label;

export default function SkinTonePage() {
  return <ChoicePage title="Skin tone" subtitle="The emoji with hands and people show this skin tone."
    options={tones}
    read={({ skinTone }) => skinTone}
    write={skinTone => ({ skinTone })} />;
}
