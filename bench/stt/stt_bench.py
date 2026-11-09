#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Compares on-device speech recognizers on the same 16 kHz clips.

Each clip is a WAV whose file name (before the extension) is the reference text. Engines:
  nemo        the NeMo streaming transducer the app used before Parakeet, through sherpa-onnx,
              fed in 100 ms frames with 0.6 s of tail padding
  nemo-cut    the same, with the clip cut right at the end of speech (a manual mic stop)
  nemo-cut-1.2  the cut clip with 1.2 s of padding instead
  asr4all-*   FUTO asr4all through transformers: one-shot transcribe and the default
              streaming session (c16r4)
  et:<bundle>:<tier>  FUTO asr4all ExecuTorch bundle (what a phone runs, e.g. int8) at a streaming
              tier, through FUTO's reference decoder (--futo-dir holds asr4all_executorch.py)
  cloud:<model>  an OpenRouter transcription model (OPENROUTER_API_KEY), whole clip per request
Prints per-clip transcripts, WER, and decode time. With --no-ref (recordings pulled from the
app's history, files/history-audio/), prints each engine's transcript for comparison by ear.
"""
import argparse, glob, os, re, sys, time

import numpy as np
import soundfile as sf

NUM = {"0": "zero", "1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "nine", "10": "ten", "1st": "first"}


FILLERS = {"um", "uhm", "uh", "oh", "ah", "er", "hmm", "mm"}
# Spellings that are the same word: not a recognition error.
SAME = {"dominoes": "dominos", "dominos": "dominos"}
CONTRACTIONS = {"it's": "it is", "don't": "do not", "can't": "cannot", "what's": "what is", "that's": "that is", "you're": "you are"}


def norm(t, lenient=False):
    if lenient:
        t = re.sub(r"([a-z])([A-Z])", r"\1 \2", t)  # getDirections, OpenApp: tool names written as code
    t = t.lower().replace("’", "'")
    if lenient:
        for a, b in CONTRACTIONS.items():
            t = re.sub(rf"\b{a}\b", b, t)
    t = re.sub(r"[^a-z0-9' ]+", " ", t)
    words = [NUM.get(w, w) for w in t.split()]
    joined = " ".join(words)
    for a, b in (("gonna", "going to"), ("i'll", "i will"), ("ill", "i will"), ("i'm", "i am")):
        joined = re.sub(rf"\b{a}\b", b, joined)
    if lenient:
        words = [SAME.get(w.replace("'", ""), w.replace("'", "")) for w in joined.split()]
        return [w for w in words if w not in FILLERS]
    return joined.split()


def wer(ref, hyp, lenient=False):
    r, h = norm(ref, lenient), norm(hyp, lenient)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)


def cut_at_speech_end(x, sr=16000):
    # Last 20 ms window above -40 dBFS, plus 60 ms.
    w = sr // 50
    n = len(x) // w
    rms = np.array([np.sqrt(np.mean(x[i * w:(i + 1) * w] ** 2) + 1e-12) for i in range(n)])
    loud = np.nonzero(20 * np.log10(rms) > -40)[0]
    end = (loud[-1] + 1) * w + sr * 60 // 1000 if len(loud) else len(x)
    return x[:end]


def nemo_engine(model_dir, padding_s, cut):
    import sherpa_onnx
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx", tokens=f"{model_dir}/tokens.txt",
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method="greedy_search",
        enable_endpoint_detection=False)

    def run(x):
        if cut:
            x = cut_at_speech_end(x)
        s = rec.create_stream()
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while rec.is_ready(s):
                rec.decode_stream(s)
        s.accept_waveform(16000, np.zeros(int(16000 * padding_s), dtype=np.float32))
        s.input_finished()
        while rec.is_ready(s):
            rec.decode_stream(s)
        return rec.get_result(s).strip().lower()
    return run


def asr4all_engine(name, streaming):
    import torch
    from transformers import AutoModel
    torch.set_num_threads(2)
    model = AutoModel.from_pretrained(f"futo-org/{name}", trust_remote_code=True).eval()

    def run(x):
        wav = torch.from_numpy(x)
        with torch.no_grad():
            if not streaming:
                return model.transcribe(wav)
            s = model.session()
            for i in range(0, len(x), 1600):
                s.accept(wav[i:i + 1600])
            return s.finish()
    return run


def online_engine(model_dir):
    """Any sherpa-onnx streaming transducer (Nemotron, Parakeet unified), fed like the app."""
    import sherpa_onnx
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx", tokens=f"{model_dir}/tokens.txt",
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method="greedy_search")

    def run(x):
        s = rec.create_stream()
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while rec.is_ready(s):
                rec.decode_stream(s)
        # Enough tail for the largest chunk (1120 ms) plus its lookahead.
        s.accept_waveform(16000, np.zeros(16000 * 2, dtype=np.float32))
        s.input_finished()
        while rec.is_ready(s):
            rec.decode_stream(s)
        return rec.get_result(s).strip()
    return run


def offline_engine(model_dir):
    """A sherpa-onnx offline NeMo transducer (Parakeet TDT): the whole clip at once."""
    import sherpa_onnx
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx", decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx", tokens=f"{model_dir}/tokens.txt",
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method="greedy_search", model_type="nemo_transducer")

    def run(x):
        s = rec.create_stream()
        s.accept_waveform(16000, x)
        rec.decode_stream(s)
        return s.result.text.strip()
    return run


def qwen3_engine(model_dir):
    import sherpa_onnx
    f = {n.split(".")[0]: os.path.join(model_dir, n) for n in os.listdir(model_dir)}
    rec = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=f["conv_frontend"], encoder=f["encoder"], decoder=f["decoder"], tokenizer=f.get("tokenizer", model_dir), num_threads=2)

    def run(x):
        s = rec.create_stream()
        s.accept_waveform(16000, x)
        rec.decode_stream(s)
        return s.result.text.strip()
    return run


def rescore(path):
    """Re-scores an earlier run's printed transcripts (strict and lenient) without running anything."""
    eng = None
    tot = {}
    for line in open(path):
        if line.startswith("== "):
            eng = line[3:].strip()
            continue
        m = re.match(r"\s+\d+/\d+(?: \d+/\d+)?\s+(['\"])(.*)\1\s+\[(.*)\]$", line.rstrip("\n"))
        if not (m and eng):
            continue
        hyp, ref = m.group(2), m.group(3)
        t = tot.setdefault(eng, [0, 0, 0, 0])
        e, n = wer(ref, hyp)
        le, ln = wer(ref, hyp, lenient=True)
        t[0] += e; t[1] += n; t[2] += le; t[3] += ln
    for eng, (e, n, le, ln) in tot.items():
        print(f"{eng:70s} strict {100 * e / n:5.1f}% ({e}/{n})   lenient {100 * le / ln:5.1f}% ({le}/{ln})")


def et_engine(futo_dir, bundle, tier):
    sys.path.insert(0, futo_dir)
    from asr4all_executorch import load_engine
    e = load_engine(os.path.join(futo_dir, bundle))
    e.set_tier(tier)

    def run(x):
        return e.decode_offline(x)
    return run


def cloud_engine(model):
    import base64, io, json, urllib.request
    import soundfile

    def run(x):
        buf = io.BytesIO()
        soundfile.write(buf, x, 16000, format="WAV", subtype="PCM_16")
        body = json.dumps({"model": model, "language": "en",
                           "input_audio": {"data": base64.b64encode(buf.getvalue()).decode(), "format": "wav"}}).encode()
        req = urllib.request.Request("https://openrouter.ai/api/v1/audio/transcriptions", body,
                                     {"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"], "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)["text"].strip()
    return run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clips", nargs="?")
    ap.add_argument("--rescore", help="re-score an earlier run's output file instead of running engines")
    ap.add_argument("--nemo-dir")
    ap.add_argument("--futo-dir")
    ap.add_argument("--no-ref", action="store_true", help="clips have no reference text (history recordings): print transcripts only")
    ap.add_argument("--engines", default="nemo,nemo-cut,nemo-cut-1.2,asr4all-s,asr4all-s-stream,asr4all-m-stream,asr4all-l-stream")
    a = ap.parse_args()
    if a.rescore:
        rescore(a.rescore)
        return
    clips = sorted(glob.glob(os.path.join(a.clips, "*.wav")))
    for eng in a.engines.split(","):
        if eng.startswith("et:"):
            _, bundle, tier = eng.split(":")
            run = et_engine(a.futo_dir, bundle, tier)
        elif eng.startswith("cloud:"):
            run = cloud_engine(eng.removeprefix("cloud:"))
        elif eng.startswith("online:"):
            run = online_engine(eng.removeprefix("online:"))
        elif eng.startswith("offline:"):
            run = offline_engine(eng.removeprefix("offline:"))
        elif eng.startswith("qwen3:"):
            run = qwen3_engine(eng.removeprefix("qwen3:"))
        elif eng.startswith("nemo"):
            pad = 1.2 if eng.endswith("1.2") else 0.6
            run = nemo_engine(a.nemo_dir, pad, cut="cut" in eng)
        else:
            run = asr4all_engine(eng.removesuffix("-stream"), streaming=eng.endswith("-stream"))
        errs = words = lerrs = lwords = 0
        audio_s = decode_s = 0.0
        print(f"== {eng}")
        for c in clips:
            x, sr = sf.read(c, dtype="float32")
            assert sr == 16000
            if a.no_ref:
                t = time.perf_counter()
                hyp = run(x)
                decode_s += time.perf_counter() - t
                audio_s += len(x) / sr
                print(f"  {os.path.basename(c)}: {hyp!r}")
                continue
            ref = re.sub(r"^\d+\.\s*", "", os.path.splitext(os.path.basename(c))[0])
            t = time.perf_counter()
            hyp = run(x)
            decode_s += time.perf_counter() - t
            audio_s += len(x) / sr
            scored = hyp
            if ref.rstrip().endswith("..."):
                # The reference stops early ("...wear..."): score the transcript up to its last word.
                last = norm(ref)[-1]
                hw = norm(hyp)
                scored = " ".join(hw[: hw.index(last) + 1] if last in hw else hw[: len(norm(ref))])
            e, n = wer(ref, scored)
            le, ln = wer(ref, scored, lenient=True)
            errs += e
            words += n
            lerrs += le
            lwords += ln
            print(f"  {e}/{n} {le}/{ln}  {hyp!r}   [{ref}]")
        if a.no_ref:
            print(f"-- {eng}: {decode_s:.2f}s for {audio_s:.1f}s audio (RTFx {audio_s / decode_s:.1f}, 2 threads)")
            continue
        print(f"-- {eng}: WER {100 * errs / words:.1f}% ({errs}/{words}), lenient {100 * lerrs / lwords:.1f}% ({lerrs}/{lwords}), {decode_s:.2f}s for {audio_s:.1f}s audio (RTFx {audio_s / decode_s:.1f}, 2 threads)")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
