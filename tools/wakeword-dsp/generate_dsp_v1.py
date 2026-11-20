#!/usr/bin/env python3
"""Generate NOID DSP v1 coefficients from the immutable Heed reference."""
import argparse, hashlib, importlib.metadata, json, re, sys
from pathlib import Path

HEED_VERSION = "0.1.4"
HEED_COMMIT = "643a93c1e6702640c515475500f2e2653bd7d621"
NUMERICAL_VERSIONS = ("1.26.4", "1.14.1", "2.5.1")
SHAPES = (("highpass_sos",(4,7)),("notch_50hz_sos",(1,7)),("notch_60hz_sos",(1,7)),("hann_window",(400,)),("mel_filterbank",(40,257)))

def verify_environment():
    import heed, numpy, scipy, torch
    if heed.__version__ != HEED_VERSION:
        raise RuntimeError("Unexpected heed package version")
    direct = json.loads(importlib.metadata.distribution("heed-wakeword").read_text("direct_url.json") or "{}")
    if direct.get("vcs_info",{}).get("commit_id") != HEED_COMMIT:
        raise RuntimeError("Heed VCS commit is not the pinned revision")
    actual = (numpy.__version__,scipy.__version__,torch.__version__.split("+")[0])
    if actual != NUMERICAL_VERSIONS:
        raise RuntimeError("Unexpected numerical dependency versions: "+repr(actual))

def f32(values,name):
    import numpy as np
    a=np.ascontiguousarray(values,dtype="<f4")
    if not np.isfinite(a).all(): raise ValueError(name+" contains non-finite values")
    return a

def build_sections():
    import numpy as np, scipy.signal
    from heed import SAMPLE_RATE,N_FFT,N_MELS,WIN_SAMPLES,HOP_SAMPLES
    from heed.audio import _hpf_coefficients,_notch_coefficients,_hann,_build_mel_filterbank
    if (SAMPLE_RATE,N_FFT,N_MELS,WIN_SAMPLES,HOP_SAMPLES)!=(16000,512,40,400,160):
        raise RuntimeError("Heed geometry does not match DSP v1")
    stages=[]
    for name,sos in (("highpass_sos",_hpf_coefficients(100.0,16000,8)),("notch_50hz_sos",_notch_coefficients(50.0,30.0,16000)),("notch_60hz_sos",_notch_coefficients(60.0,30.0,16000))):
        zi=scipy.signal.sosfilt_zi(sos)
        if sos.shape[1]!=6 or zi.shape!=(sos.shape[0],2) or not np.allclose(sos[:,3],1.0,rtol=0,atol=1e-14):
            raise ValueError("Invalid SOS geometry or normalization: "+name)
        # Store unit steady-state zi. Runtime scales each stage's zi by that
        # stage's first input sample, matching Heed's causal streaming filter.
        stages.append((name,f32(np.concatenate((sos[:,:3],sos[:,4:6],zi),axis=1),name)))
    stages += [("hann_window",f32(_hann().detach().cpu().numpy(),"hann_window")),("mel_filterbank",f32(_build_mel_filterbank(n_mels=40,n_fft=512).detach().cpu().numpy(),"mel_filterbank"))]
    for (name,a),(expected,shape) in zip(stages,SHAPES):
        if name!=expected or tuple(a.shape)!=shape: raise ValueError("Wrong stage shape: "+name+" "+repr(a.shape))
    if sum(a.size for _,a in stages)!=10722: raise ValueError("Wrong element count")
    return stages

def generate(profile_id):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}",profile_id): raise ValueError("Unsafe profile id")
    verify_environment()
    chunks=[]; entries=[]; offset=0
    for name,a in build_sections():
        raw=a.tobytes(order="C")
        entries.append({"name":name,"offset_bytes":offset,"element_count":int(a.size),"shape":list(a.shape),"sha256":hashlib.sha256(raw).hexdigest()})
        chunks.append(raw); offset+=len(raw)
    payload=b"".join(chunks)
    if len(payload)!=42888: raise ValueError("Wrong payload byte length")
    descriptor={"schema_version":1,"profile_id":profile_id,"payload_file":profile_id+".dsp.bin","payload_bytes":len(payload),"payload_sha256":hashlib.sha256(payload).hexdigest(),"byte_order":"little_endian","scalar_format":"ieee754_float32","sample_rate_hz":16000,"fft_size":512,"window_samples":400,"hop_samples":160,"mel_bins":40,"sections":entries}
    desc=(json.dumps(descriptor,ensure_ascii=False,sort_keys=True,separators=(",",":"))+"\n").encode("utf-8")
    if len(desc)>16384: raise ValueError("Descriptor too large")
    return desc,payload

def main():
    p=argparse.ArgumentParser(); p.add_argument("--profile-id",required=True); p.add_argument("--output-dir",type=Path,required=True); a=p.parse_args()
    desc,payload=generate(a.profile_id); a.output_dir.mkdir(parents=True,exist_ok=True)
    paths=(a.output_dir/(a.profile_id+".dsp.json"),a.output_dir/(a.profile_id+".dsp.bin"))
    if any(x.exists() for x in paths): raise FileExistsError("Refusing to overwrite generated artifacts")
    paths[0].write_bytes(desc); paths[1].write_bytes(payload)
    print("descriptor_sha256="+hashlib.sha256(desc).hexdigest()); print("payload_sha256="+hashlib.sha256(payload).hexdigest()); print("payload_bytes="+str(len(payload)))

if __name__=="__main__":
    try: main()
    except Exception as e: print("generation failed: "+str(e),file=sys.stderr); raise SystemExit(2)
