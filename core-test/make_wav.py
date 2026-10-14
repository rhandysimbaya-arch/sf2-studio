"""Makes a 22050 Hz mono 16-bit sine WAV with a 'smpl' chunk (root note 69, loop 1000..20000)."""
import math
import struct
import sys

rate = 22050
n = 22050
pcm = b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(n))

fmt = struct.pack("<HHIIHH", 1, 1, rate, rate * 2, 2, 16)
smpl = struct.pack("<IIIIIIIII", 0, 0, 1000000000 // rate, 69, 0, 0, 0, 1, 0)
smpl += struct.pack("<IIIIII", 0, 0, 1000, 20000, 0, 0)


def chunk(cid, body):
    out = cid + struct.pack("<I", len(body)) + body
    return out + (b"\0" if len(body) % 2 else b"")


body = b"WAVE" + chunk(b"fmt ", fmt) + chunk(b"smpl", smpl) + chunk(b"data", pcm)
open(sys.argv[1], "wb").write(b"RIFF" + struct.pack("<I", len(body)) + body)
print("wrote", sys.argv[1])
