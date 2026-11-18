import { Row, Screen } from "ink";
import { arrowOutward } from "ink/icons";

const licences = [
  { title: "FUTO Swipe models", subtitle: "FUTO Model Weights License", url: "https://huggingface.co/futo-org/futo-swipe" },
  { title: "FUTO Swipe library", subtitle: "GPL-3.0. Atlas uses a patched copy of its Android code.", url: "https://gitlab.futo.org/keyboard/swipe-library" },
  { title: "FUTO Keyboard", subtitle: "FUTO Source First License", url: "https://github.com/futo-org/android-keyboard" },
  { title: "FUTO native libraries", subtitle: "ExecuTorch and other dependencies", url: "https://github.com/pytorch/executorch" },
  { title: "Android NDK", subtitle: "LLVM notice", url: "https://llvm.org/LICENSE.txt" },
  { title: "AOSP dictionary", subtitle: "Apache 2.0", url: "https://codeberg.org/Helium314/aosp-dictionaries" },
  { title: "Ink", subtitle: "MIT", url: "https://github.com/No-Scrolling/ink" },
  { title: "sherpa-onnx", subtitle: "Apache 2.0. Runs the speech to text models.", url: "https://github.com/k2-fsa/sherpa-onnx" },
  { title: "ONNX Runtime", subtitle: "MIT. Part of sherpa-onnx.", url: "https://onnxruntime.ai" },
  { title: "Apache Commons Compress", subtitle: "Apache 2.0. Unpacks speech models.", url: "https://commons.apache.org/proper/commons-compress/" },
  { title: "Inter", subtitle: "SIL Open Font Licence", url: "https://github.com/rsms/inter" },
  { title: "Remix Icon", subtitle: "Apache 2.0", url: "https://github.com/Remix-Design/RemixIcon" },
  { title: "Unicode data", subtitle: "Unicode License 3.0", url: "https://www.unicode.org/license.txt" },
];

export default function Licences() {
  return <Screen title="Licences">
    {licences.map(licence => <Row key={licence.title} {...licence} endIcon={arrowOutward} />)}
  </Screen>;
}
