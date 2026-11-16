import type { SpeakerProfile, State } from "../../lib/atlas";
import { ChoicePage } from "../../lib/choice";

const profiles: { value: SpeakerProfile; label: string }[] = [
  { value: "atlas", label: "Atlas" },
  { value: "clear", label: "Clear" },
  { value: "warm", label: "Warm" },
  { value: "voice", label: "Voice" },
  { value: "natural", label: "Natural" },
  { value: "quiet", label: "Quiet" },
  { value: "heybroo0", label: "heybroo0" },
];

export const speakerProfileLabel = ({ speakerProfile }: State) =>
  profiles.find(profile => profile.value === speakerProfile)?.label ?? profiles[0].label;

export default function SpeakerProfilePage() {
  return <ChoicePage title="Profile" subtitle="Clear is for music. Warm is softer. Voice is for podcasts and videos. Natural changes the sound less. Quiet is for low volume. heybroo0 has more bass and treble."
    options={profiles}
    read={({ speakerProfile }) => speakerProfile}
    write={speakerProfile => ({ speakerProfile })} />;
}
