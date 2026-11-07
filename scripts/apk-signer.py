import sys, struct, hashlib
data = open(sys.argv[1], 'rb').read()
eocd = data.rfind(b'PK\x05\x06')
cd_off = struct.unpack('<I', data[eocd+16:eocd+20])[0]
assert data[cd_off-16:cd_off] == b'APK Sig Block 42', 'no signing block'
size = struct.unpack('<Q', data[cd_off-24:cd_off-16])[0]
pairs = data[cd_off - size - 8 + 8:cd_off-24]
def u32(b, o): return struct.unpack('<I', b[o:o+4])[0]
i = 0
while i < len(pairs):
    ln = struct.unpack('<Q', pairs[i:i+8])[0]; pid = u32(pairs, i+8); val = pairs[i+12:i+8+ln]; i += 8 + ln
    if pid not in (0x7109871a, 0xf05368c0): continue
    signers = val[4:4+u32(val,0)]
    signer = signers[4:4+u32(signers,0)]
    sd = signer[4:4+u32(signer,0)]
    dig_len = u32(sd,0); certs = sd[4+dig_len:]
    certs = certs[4:4+u32(certs,0)]
    cert = certs[4:4+u32(certs,0)]
    print(hex(pid), ':'.join('%02X' % b for b in hashlib.sha256(cert).digest()))
